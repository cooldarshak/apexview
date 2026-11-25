//! The clear CMAF compositor for an explicit, checked tile placement.
//! A session owns the validated configuration. Frame data borrows only
//! the corresponding media fragment and is written into the caller's output.
use crate::bmff::{Error, MAX_BYTES};
use crate::clear_cmaf::{self, NalSample};
use crate::clear_slice::Parts;
use crate::cmaf_init;
use crate::cmaf_input::{Fragment, HevcConfiguration, Initialization, Sample, TrackContext};
use crate::hevc_nal::NalUnits;
use crate::hevc_output;
use crate::hevc_parameters::Parameters;
use crate::hevc_poc::{Identity, Tracker};
use crate::hevc_slice::Slice;

const MAX_FEEDS: usize = 24;
const MAX_COLUMNS: usize = 20;
const MAX_ROWS: usize = 22;
const MAX_SEGMENT_SLICES: usize = 16_384;

#[derive(Clone, Copy)]
pub struct Cell {
    pub feed: usize,
    pub tile: usize,
}

pub struct Grid<'a> {
    pub width: u32,
    pub height: u32,
    pub columns: &'a [u32],
    pub rows: &'a [u32],
    /// One cell for each output tile in raster order.
    pub cells: &'a [Cell],
    pub frame_rate: (u32, u32),
}

struct Source {
    context: TrackContext,
    nal_prefix: usize,
    parameter_sets: [Vec<u8>; 3],
    parameters: Parameters,
    tiles: Vec<Tile>,
}

#[derive(Clone, Copy)]
struct Tile {
    address: u32,
    width: u32,
    height: u32,
}

struct Placement {
    cell: Cell,
    address: u32,
}

pub struct Atlas {
    sources: Vec<Source>,
    output: Parameters,
    placements: Vec<Placement>,
    track: u32,
    initialization: Vec<u8>,
}

impl Atlas {
    /// Use conservative normalized parameter-set equality so different coding
    /// profiles never enter the compositor on the strength of geometry alone.
    pub fn new(inputs: &[&[u8]], grid: Grid<'_>, track: u32) -> Result<Self, Error> {
        if !(2..=MAX_FEEDS).contains(&inputs.len())
            || !(1..=MAX_COLUMNS).contains(&grid.columns.len())
            || !(1..=MAX_ROWS).contains(&grid.rows.len())
            || grid.columns.len() * grid.rows.len() != grid.cells.len()
        {
            return Err(Error::Limit);
        }
        require_bounded_bytes(inputs)?;
        let mut sources = Vec::new();
        sources
            .try_reserve_exact(inputs.len())
            .map_err(|_| Error::Limit)?;
        for input in inputs {
            let init = Initialization::parse(input)?;
            let sets = init.configuration.parameter_sets();
            let parameters = Parameters::parse(sets)?;
            // Match the JVM's F1/Tizen profile. Cross-feed temporal motion
            // prediction has not been proven safe for lossless stitching.
            if parameters.sps.temporal_mvp {
                return Err(Error::Unsupported);
            }
            let mut parameter_sets = [Vec::new(), Vec::new(), Vec::new()];
            for (owned, source) in parameter_sets.iter_mut().zip(sets) {
                let source = trim_terminal_zeros(source)?;
                owned.try_reserve_exact(source.len()).map_err(|_| Error::Limit)?;
                owned.extend_from_slice(source);
            }
            let tiles = tile_locations(&parameters)?;
            sources.push((
                init,
                Source {
                    context: init.fragment_context(),
                    nal_prefix: init.configuration.nal_length_size(),
                    parameter_sets,
                    parameters,
                    tiles,
                },
            ));
        }
        // The reference output initialization declares four-byte NAL lengths.
        // Secondary inputs may have another legal length width, but a first
        // source with a shorter prefix must stay on the existing JVM route.
        if sources[0].1.nal_prefix != 4 {
            return Err(Error::Unsupported);
        }
        let template_index = grid.cells.first().ok_or(Error::Metadata)?.feed;
        let (template_init, template) = sources.get(template_index).ok_or(Error::Metadata)?;
        let timescale = template_init.timescale;
        let prefix = template.parameters.prefix;
        if sources.iter().any(|(init, source)| {
            init.timescale != timescale || source.parameters.prefix.log2_ctb != prefix.log2_ctb
        }) {
            return Err(Error::Unsupported);
        }
        let uncropped = sources.iter().any(|(_, source)| {
            let candidate = source.parameters.prefix;
            candidate.coded_width != prefix.coded_width
                || candidate.coded_height != prefix.coded_height
                || candidate.display_width != prefix.display_width
                || candidate.display_height != prefix.display_height
        });
        let request = hevc_output::Request {
            ids: [0; 3],
            width: grid.width,
            height: grid.height,
            columns: grid.columns,
            rows: grid.rows,
            frame_rate: grid.frame_rate,
            loop_across_tiles: false,
            uncropped,
        };
        let generated = request.generate(template_init.configuration.parameter_sets())?;
        for (index, (init, _)) in sources.iter().enumerate() {
            if index != template_index
                && request.generate(init.configuration.parameter_sets())?.nals != generated.nals
            {
                return Err(Error::Unsupported);
            }
        }
        let output_tiles = tile_locations(&generated.parameters)?;
        let mut placements = Vec::new();
        placements
            .try_reserve_exact(grid.cells.len())
            .map_err(|_| Error::Limit)?;
        let mut represented = [false; MAX_FEEDS];
        for (&cell, tile) in grid.cells.iter().zip(output_tiles) {
            let source_tile = sources
                .get(cell.feed)
                .and_then(|(_, source)| source.tiles.get(cell.tile))
                .ok_or(Error::Metadata)?;
            if source_tile.width != tile.width || source_tile.height != tile.height {
                return Err(Error::Unsupported);
            }
            represented[cell.feed] = true;
            placements.push(Placement {
                cell,
                address: tile.address,
            });
        }
        if represented[..sources.len()].contains(&false) {
            return Err(Error::Metadata);
        }
        let hvcc = generated.hvcc()?;
        let init_request = cmaf_init::Request {
            track_id: track,
            timescale,
            width: u16::try_from(generated.parameters.prefix.display_width)
                .map_err(|_| Error::Limit)?,
            height: u16::try_from(generated.parameters.prefix.display_height)
                .map_err(|_| Error::Limit)?,
            handler_name: "ApexView HEVC",
            configuration: HevcConfiguration::parse(&hvcc)?,
        };
        let mut initialization = Vec::new();
        let size = init_request.size()?;
        initialization
            .try_reserve_exact(size)
            .map_err(|_| Error::Limit)?;
        initialization.resize(size, 0);
        init_request.write(&mut initialization)?;
        Ok(Self {
            sources: sources.into_iter().map(|(_, source)| source).collect(),
            output: generated.parameters,
            placements,
            track,
            initialization,
        })
    }

    pub fn initialization(&self) -> &[u8] {
        &self.initialization
    }

    pub fn write_fragment(
        &self,
        input: &[&[u8]],
        sequence: u32,
        output: &mut [u8],
    ) -> Result<usize, Error> {
        if input.len() != self.sources.len() {
            return Err(Error::Metadata);
        }
        require_bounded_bytes(input)?;
        let mut fragments = Vec::new();
        fragments
            .try_reserve_exact(input.len())
            .map_err(|_| Error::Limit)?;
        for (source, &media) in self.sources.iter().zip(input) {
            fragments.push(Fragment::parse_with_context(media, source.context)?);
        }
        let reference = &fragments[0];
        if fragments
            .iter()
            .any(|fragment| fragment.samples.len() != reference.samples.len())
        {
            return Err(Error::Timing);
        }
        let source_tiles = self
            .sources
            .iter()
            .map(|source| source.tiles.len())
            .sum::<usize>();
        let pictures = reference.samples.len();
        if pictures.saturating_mul(source_tiles + self.placements.len()) > MAX_SEGMENT_SLICES {
            return Err(Error::Limit);
        }
        let mut trackers = [Tracker::default(); MAX_FEEDS];
        let mut picture_parts = Vec::new();
        picture_parts
            .try_reserve_exact(pictures)
            .map_err(|_| Error::Limit)?;
        let mut output_bytes = 0usize;
        for (index, source_sample) in reference.samples.iter().enumerate() {
            let mut feed_slices = Vec::new();
            feed_slices
                .try_reserve_exact(self.sources.len())
                .map_err(|_| Error::Limit)?;
            let mut order: Option<Identity> = None;
            for (feed, (source, fragment)) in self.sources.iter().zip(&fragments).enumerate() {
                let incoming = &fragment.samples[index];
                match_timing(source_sample, incoming)?;
                let slices = source.slices(incoming)?;
                let picture = slices[0].prefix.picture.ok_or(Error::Metadata)?;
                if index == 0
                    && (!(16..=21).contains(&picture.nal_type) || incoming.flags & 0x1_0000 != 0)
                {
                    return Err(Error::Unsupported);
                }
                if slices.iter().any(|slice| {
                    slice.prefix.picture != Some(picture)
                        || slice.references != slices[0].references
                        || slice.prefix.no_output_of_prior != slices[0].prefix.no_output_of_prior
                }) || feed_slices.first().is_some_and(|first: &Vec<Slice<'_>>| {
                    first[0].prefix.picture != Some(picture)
                        || first[0].references != slices[0].references
                        || first[0].prefix.no_output_of_prior != slices[0].prefix.no_output_of_prior
                }) {
                    return Err(Error::Metadata);
                }
                let position = trackers[feed].advance(
                    picture,
                    source.parameters.prefix.poc_lsb_bits,
                    index == 0,
                )?;
                if order.is_some_and(|previous| previous != position) {
                    return Err(Error::Timing);
                }
                order = Some(position);
                feed_slices.push(slices);
            }
            let mut nals = Vec::<Parts<'_>>::new();
            nals.try_reserve_exact(self.placements.len())
                .map_err(|_| Error::Limit)?;
            for placement in &self.placements {
                let Cell { feed, tile } = placement.cell;
                let nal =
                    self.output
                        .rewrite_tile(&feed_slices[feed][tile], placement.address, None)?;
                output_bytes = output_bytes
                    .checked_add(nal.header.len() + nal.tail.len() + 4)
                    .ok_or(Error::Limit)?;
                if output_bytes > output.len().min(MAX_BYTES) {
                    return Err(Error::Limit);
                }
                nals.push(nal);
            }
            picture_parts.push(nals);
        }
        let mut samples = Vec::new();
        samples
            .try_reserve_exact(pictures)
            .map_err(|_| Error::Limit)?;
        for (sample, nals) in reference.samples.iter().zip(&picture_parts) {
            samples.push(NalSample {
                nals,
                duration: sample.duration,
                flags: sample.flags,
                cto: i32::try_from(sample.composition_offset).map_err(|_| Error::Timing)?,
            });
        }
        clear_cmaf::write_nal_parts(
            self.track,
            sequence,
            reference.decode_time as u64,
            &samples,
            4,
            output,
        )
        .ok_or(Error::Limit)
    }
}

impl Source {
    fn slices<'b>(&self, sample: &Sample<'b>) -> Result<Vec<Slice<'b>>, Error> {
        let mut slices = Vec::new();
        slices
            .try_reserve_exact(self.tiles.len())
            .map_err(|_| Error::Limit)?;
        for unit in NalUnits::new(sample.bytes, self.nal_prefix)? {
            let (nal, header) = unit?;
            if header.layer_id != 0 ||
                u32::from(header.temporal_id_plus_one) > self.parameters.prefix.max_sub_layers
            {
                return Err(Error::Unsupported);
            }
            let nal = trim_terminal_zeros(nal)?;
            match header.unit_type {
                32..=34 => {
                    // Require exact in-band parameter identity. Even a
                    // semantically equivalent but differently encoded set
                    // takes the existing conservative JVM fallback.
                    if nal != self.parameter_sets[usize::from(header.unit_type - 32)].as_slice() {
                        return Err(Error::Metadata);
                    }
                }
                35..=40 => {} // reference discards AUD/EOS/EOB/filler/SEI
                0..=31 => {
                    let index = slices.len();
                    let tile = self.tiles.get(index).ok_or(Error::Metadata)?;
                    let slice = self.parameters.read_slice(nal)?;
                    if slice.prefix.first != (index == 0)
                        || slice.prefix.address != tile.address
                        || slice.prefix.picture.is_none()
                        || slice.entry_points != 0
                    {
                        return Err(Error::Unsupported);
                    }
                    slices.push(slice);
                }
                _ => return Err(Error::Unsupported),
            }
        }
        if slices.len() != self.tiles.len() {
            return Err(Error::Metadata);
        }
        Ok(slices)
    }
}

/// JVM's Annex-B-equivalent composition strips terminal zero bytes before
/// rewriting a clear NAL. Do so on a borrowed range, not a full sample copy.
fn trim_terminal_zeros(nal: &[u8]) -> Result<&[u8], Error> {
    let end = nal.iter().rposition(|&byte| byte != 0).map_or(0, |i| i + 1);
    if end < 2 {
        return Err(Error::Nal);
    }
    Ok(&nal[..end])
}

fn tile_locations(parameters: &Parameters) -> Result<Vec<Tile>, Error> {
    let columns = &parameters.tile_columns;
    let rows = &parameters.tile_rows;
    if columns.len() > MAX_COLUMNS || rows.len() > MAX_ROWS {
        return Err(Error::Limit);
    }
    let prefix = parameters.prefix;
    let ctb = 1 << prefix.log2_ctb;
    let mut result = Vec::new();
    result
        .try_reserve_exact(columns.len() * rows.len())
        .map_err(|_| Error::Limit)?;
    let mut top = 0;
    for height in rows {
        let mut left = 0;
        for width in columns {
            result.push(Tile {
                address: top * prefix.width_in_ctbs + left,
                width: (width * ctb).min(prefix.coded_width - left * ctb),
                height: (height * ctb).min(prefix.coded_height - top * ctb),
            });
            left += width;
        }
        top += height;
    }
    Ok(result)
}

fn match_timing(a: &Sample<'_>, b: &Sample<'_>) -> Result<(), Error> {
    if (
        a.duration,
        a.flags,
        a.decode_time,
        a.composition_offset,
        a.presentation_time,
    ) != (
        b.duration,
        b.flags,
        b.decode_time,
        b.composition_offset,
        b.presentation_time,
    ) {
        return Err(Error::Timing);
    }
    Ok(())
}

fn require_bounded_bytes(chunks: &[&[u8]]) -> Result<(), Error> {
    let total = chunks
        .iter()
        .try_fold(0usize, |total, bytes| total.checked_add(bytes.len()))
        .ok_or(Error::Limit)?;
    if total > MAX_BYTES {
        return Err(Error::Limit);
    }
    Ok(())
}
