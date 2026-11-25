//! Clear worker packet. All fields are big-endian and borrow bounded input.
//! GBC1 output is [initialization size, media size, initialization, media].
//! GBC2 retains the header, then length-prefixes each of 2..=4 media fragments.
//! Each source block carries a bounded window beginning at random access.
use crate::bmff::{Error, Reader, MAX_BYTES};
use crate::clear_atlas::{Atlas, Cell, Grid, MAX_FEEDS};
use crate::hevc_poc::Tracker;

const MAGIC: &[u8; 4] = b"GBC1";
const WINDOW_MAGIC: &[u8; 4] = b"GBC2";
const MAX_WINDOW: usize = 4;
const OUTPUT_HEADER: usize = 8;

/// Conservative workspace ceiling for this worker command in addition to the
/// worker stack, fixed slots and bridge buffers. Capture from one bounded job.
pub(crate) fn workspace_reserve(input_capacity: usize, output_capacity: usize) -> Option<usize> {
    input_capacity
        .checked_mul(4)?
        .checked_add(output_capacity)?
        .checked_add(8 * 1024 * 1024)
}

#[derive(Default)]
pub(crate) struct Dispatcher {
    cached: Option<Cached>,
}

struct Cached {
    generation: i64,
    track: u32,
    geometry: Vec<u8>,
    inits: Vec<Vec<u8>>,
    atlas: Atlas,
}

impl Dispatcher {
    pub fn invalidate(&mut self, generation: i64) {
        if self
            .cached
            .as_ref()
            .is_some_and(|cache| cache.generation != generation)
        {
            self.cached = None;
        }
    }

    pub fn run(
        &mut self,
        generation: i64,
        input: &[u8],
        output: &mut [u8],
    ) -> Result<usize, Error> {
        if input.len() > MAX_BYTES || output.len() <= OUTPUT_HEADER {
            return Err(Error::Limit);
        }
        let mut reader = Reader::new(input);
        let window = match reader.take(4)? {
            magic if magic == MAGIC => false,
            magic if magic == WINDOW_MAGIC => true,
            _ => return Err(Error::Unsupported),
        };
        let track = reader.u32()?;
        let sequence = reader.u32()?;
        if track == 0 || sequence == 0 {
            return Err(Error::Metadata);
        }
        let width = reader.u32()?;
        let height = reader.u32()?;
        let rate = (reader.u32()?, reader.u32()?);
        let feeds = usize::from(reader.u16()?);
        let column_count = usize::from(reader.u16()?);
        let row_count = usize::from(reader.u16()?);
        let field = usize::from(reader.u16()?);
        let count = if window { field } else { 1 };
        if !(2..=MAX_FEEDS).contains(&feeds)
            || !(1..=20).contains(&column_count)
            || !(1..=22).contains(&row_count)
            || (window && !(2..=MAX_WINDOW).contains(&count))
            || (!window && field != 0)
        {
            return Err(Error::Limit);
        }
        let mut columns = [0; 20];
        let mut rows = [0; 22];
        for item in &mut columns[..column_count] {
            *item = reader.u32()?;
        }
        for item in &mut rows[..row_count] {
            *item = reader.u32()?;
        }
        let mut cells = [Cell { feed: 0, tile: 0 }; 440];
        let cells = &mut cells[..column_count * row_count];
        for cell in &mut *cells {
            cell.feed = usize::from(reader.u16()?);
            cell.tile = usize::from(reader.u16()?);
        }
        // The packet's changing sequence is excluded. Assembly-relevant header,
        // layout and init byte identities are included without a hash collision.
        let geometry = &input[12..input.len() - reader.rest().len()];
        let mut inits = [&[][..]; MAX_FEEDS];
        let mut media = [[&[][..]; MAX_FEEDS]; MAX_WINDOW];
        for (feed, init) in inits[..feeds].iter_mut().enumerate() {
            let init_size = reader.u32()? as usize;
            let media_size = reader.u32()? as usize;
            *init = reader.take(init_size)?;
            let block = reader.take(media_size)?;
            if window {
                let mut parts = Reader::new(block);
                for row in &mut media[..count] {
                    let size = parts.u32()? as usize;
                    row[feed] = parts.take(size)?;
                }
                parts.finish()?;
            } else {
                media[0][feed] = block;
            }
        }
        reader.finish()?;
        self.invalidate(generation);
        let reusable = self.cached.as_ref().is_some_and(|cache| {
            cache.track == track
                && cache.geometry == geometry
                && cache
                    .inits
                    .iter()
                    .map(Vec::as_slice)
                    .eq(inits[..feeds].iter().copied())
        });
        if !reusable {
            self.cached = None;
            let atlas = Atlas::new(
                &inits[..feeds],
                Grid {
                    width,
                    height,
                    columns: &columns[..column_count],
                    rows: &rows[..row_count],
                    cells,
                    frame_rate: rate,
                },
                track,
            )?;
            let mut copies = Vec::new();
            copies.try_reserve_exact(feeds).map_err(|_| Error::Limit)?;
            for init in &inits[..feeds] {
                copies.push(copy_bytes(init)?);
            }
            self.cached = Some(Cached {
                generation,
                track,
                geometry: copy_bytes(geometry)?,
                inits: copies,
                atlas,
            });
        }
        let atlas = &self.cached.as_ref().ok_or(Error::Metadata)?.atlas;
        let init = atlas.initialization();
        let media_offset = OUTPUT_HEADER + init.len();
        let mut cursor = media_offset;
        let mut trackers = [Tracker::default(); MAX_FEEDS];
        let mut previous_end = None;
        for (index, row) in media[..count].iter().enumerate() {
            let number = sequence.checked_add(index as u32).ok_or(Error::Limit)?;
            let start = cursor.checked_add(if window { 4 } else { 0 }).ok_or(Error::Limit)?;
            let destination = output.get_mut(start..).ok_or(Error::Limit)?;
            let (size, end) = atlas.write_window_fragment(
                &row[..feeds], number, destination, &mut trackers, previous_end,
            )?;
            if window {
                output[cursor..start].copy_from_slice(&(size as u32).to_be_bytes());
            }
            cursor = start.checked_add(size).ok_or(Error::Limit)?;
            previous_end = Some(end);
        }
        let size = cursor - media_offset;
        output[..4].copy_from_slice(&(init.len() as u32).to_be_bytes());
        output[4..OUTPUT_HEADER].copy_from_slice(&(size as u32).to_be_bytes());
        output[OUTPUT_HEADER..media_offset].copy_from_slice(init);
        Ok(cursor)
    }
}

fn copy_bytes(bytes: &[u8]) -> Result<Vec<u8>, Error> {
    let mut copy = Vec::new();
    copy.try_reserve_exact(bytes.len())
        .map_err(|_| Error::Limit)?;
    copy.extend_from_slice(bytes);
    Ok(copy)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncated_and_foreign_packets_cannot_mutate_output() {
        let mut worker = Dispatcher::default();
        let mut output = [0x77; 32];
        for source in [b"GBC1".as_slice(), b"GBE1", b"GBC1\0\0\0\x01"] {
            assert!(worker.run(1, source, &mut output).is_err());
            assert_eq!(output, [0x77; 32]);
        }
        assert_eq!(workspace_reserve(usize::MAX, 1), None);
    }
}
