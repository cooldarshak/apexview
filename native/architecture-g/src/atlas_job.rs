//! Clear worker packet. All fields are big-endian and borrow bounded input.
//! GBC1 output is [initialization size, media size, initialization, media].
//! GBC2 retains the header, then length-prefixes each of 2..=4 media fragments.
//! GBC3 uses the same single/window layouts with an explicit filter-enabled
//! request; Rust verifies every output seam and every selected slice.
//! Each source block carries a bounded window beginning at random access.
use crate::bmff::{Error, Reader, MAX_ATLAS_BYTES};
use crate::clear_atlas::{Atlas, Cell, FragmentBoundary, Grid, MAX_FEEDS};
use crate::hevc_poc::Tracker;

const MAGIC: &[u8; 4] = b"GBC1";
const WINDOW_MAGIC: &[u8; 4] = b"GBC2";
const FILTER_MAGIC: &[u8; 4] = b"GBC3";
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
    header_slab: Vec<u8>,
}

struct Cached {
    generation: i64,
    track: u32,
    filter: bool,
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
            self.header_slab = Vec::new();
        }
    }

    pub fn run(
        &mut self,
        generation: i64,
        input: &[u8],
        output: &mut [u8],
    ) -> Result<usize, Error> {
        if input.len() > MAX_ATLAS_BYTES || output.len() > MAX_ATLAS_BYTES {
            return Err(Error::Limit);
        }
        if output.len() <= OUTPUT_HEADER { return Err(Error::Capacity); }
        let mut reader = Reader::new(input);
        let magic = reader.take(4)?;
        let filter = magic == FILTER_MAGIC;
        if magic != MAGIC && magic != WINDOW_MAGIC && !filter {
            return Err(Error::Unsupported);
        }
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
        let window = magic == WINDOW_MAGIC || filter && field > 1;
        let count = if window { field } else { 1 };
        if !(2..=MAX_FEEDS).contains(&feeds)
            || !(1..=20).contains(&column_count)
            || !(1..=22).contains(&row_count)
            || (window && !(2..=MAX_WINDOW).contains(&count))
            || (!window && field != if filter { 1 } else { 0 })
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
            cache.track == track && cache.filter == filter
                && cache.geometry == geometry
                && cache
                    .inits
                    .iter()
                    .map(Vec::as_slice)
                    .eq(inits[..feeds].iter().copied())
        });
        if !reusable {
            self.cached = None;
            let atlas = Atlas::new_with_filter(
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
                filter,
            )?;
            let mut copies = Vec::new();
            copies.try_reserve_exact(feeds).map_err(|_| Error::Limit)?;
            for init in &inits[..feeds] {
                copies.push(copy_bytes(init)?);
            }
            self.cached = Some(Cached {
                generation,
                track,
                filter,
                geometry: copy_bytes(geometry)?,
                inits: copies,
                atlas,
            });
        }
        let atlas = &self.cached.as_ref().ok_or(Error::Metadata)?.atlas;
        let init = atlas.initialization();
        let media_offset = OUTPUT_HEADER + init.len();
        if media_offset > output.len() { return Err(Error::Capacity); }
        let mut cursor = media_offset;
        let mut trackers = [Tracker::default(); MAX_FEEDS];
        let mut previous_end = None;
        let mut can_gate_filter = true;
        for (index, row) in media[..count].iter().enumerate() {
            let number = sequence.checked_add(index as u32).ok_or(Error::Limit)?;
            let start = cursor.checked_add(if window { 4 } else { 0 }).ok_or(Error::Limit)?;
            let destination = output.get_mut(start..).ok_or(Error::Capacity)?;
            let (size, end, gate) = atlas.write_window_fragment(
                &row[..feeds], number, destination, &mut trackers,
                FragmentBoundary { previous_end, standalone: count == 1 }, &mut self.header_slab,
            )?;
            can_gate_filter &= gate;
            if window {
                output[cursor..start].copy_from_slice(&(size as u32).to_be_bytes());
            }
            cursor = start.checked_add(size).ok_or(Error::Limit)?;
            previous_end = Some(end);
        }
        if filter != can_gate_filter { return Err(Error::Metadata); }
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
