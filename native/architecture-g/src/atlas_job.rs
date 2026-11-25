//! Clear worker packet. All fields are big-endian and borrow bounded input.
//! The output is [initialization size, media size, initialization, media].
use crate::bmff::{Error, Reader, MAX_BYTES};
use crate::clear_atlas::{Atlas, Cell, Grid};

const MAGIC: &[u8; 4] = b"GBC1";
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
        if reader.take(4)? != MAGIC {
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
        if !(2..=24).contains(&feeds)
            || !(1..=20).contains(&column_count)
            || !(1..=22).contains(&row_count)
            || reader.u16()? != 0
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
        let mut inits = [&[][..]; 24];
        let mut media = [&[][..]; 24];
        for (init, media) in inits[..feeds].iter_mut().zip(&mut media[..feeds]) {
            let init_size = reader.u32()? as usize;
            let media_size = reader.u32()? as usize;
            *init = reader.take(init_size)?;
            *media = reader.take(media_size)?;
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
        let media_output = output.get_mut(media_offset..).ok_or(Error::Limit)?;
        let size = atlas.write_fragment(&media[..feeds], sequence, media_output)?;
        output[..4].copy_from_slice(&(init.len() as u32).to_be_bytes());
        output[4..OUTPUT_HEADER].copy_from_slice(&(size as u32).to_be_bytes());
        output[OUTPUT_HEADER..media_offset].copy_from_slice(init);
        Ok(media_offset + size)
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
