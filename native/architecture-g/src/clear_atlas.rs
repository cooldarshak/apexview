//! The clear CMAF compositor for an explicit, checked tile placement.
//! A session owns the validated configuration. Frame data borrows only
//! the corresponding media fragment and is written into the caller's output.
use crate::bmff::{Error, MAX_ATLAS_BYTES};
use crate::clear_cmaf::{self, NalSample};
use crate::clear_slice::Parts;
use crate::cmaf_init;
use crate::cmaf_input::{Fragment, HevcConfiguration, Initialization, Sample, TrackContext};
use crate::hevc_nal::NalUnits;
use crate::hevc_output;
use crate::hevc_parameters::Parameters;
use crate::hevc_poc::{Identity, Tracker};
use crate::hevc_slice::Slice;

pub(crate) const MAX_FEEDS: usize = 24;
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
    timescale: u32,
    nal_prefix: usize,
    parameter_sets: [Vec<u8>; 3],
    parameter_identity: hevc_output::ParameterIdentity,
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
    own_seams: bool,
    output_nal_prefix: usize,
    initialization: Vec<u8>,
}

impl Atlas {
    /// Use conservative normalized parameter-set equality so different coding
    /// profiles never enter the compositor on the strength of geometry alone.
    pub fn new(inputs: &[&[u8]], grid: Grid<'_>, track: u32) -> Result<Self, Error> {
        Self::new_with_filter(inputs, grid, track, false)
    }

    pub fn new_with_filter(inputs: &[&[u8]], grid: Grid<'_>, track: u32,
                           loop_across_tiles: bool) -> Result<Self, Error> {
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
                    timescale: init.timescale,
                    nal_prefix: init.configuration.nal_length_size(),
                    parameter_identity: hevc_output::parameter_identity(
                        parameter_sets.each_ref().map(Vec::as_slice), &parameters, false,
                    )?,
                    parameter_sets,
                    parameters,
                    tiles,
                },
            ));
        }
        let output_nal_prefix = sources[0].1.nal_prefix;
        let template_index = grid.cells.first().ok_or(Error::Metadata)?.feed;
        let (template_init, template) = sources.get(template_index).ok_or(Error::Metadata)?;
        let timescale = sources[0].0.timescale;
        let prefix = template.parameters.prefix;
        if sources.iter().any(|(_, source)| {
            source.parameters.prefix.log2_ctb != prefix.log2_ctb
        }) {
            return Err(Error::Unsupported);
        }
        let uncropped = sources.iter().any(|(_, source)| {
            let candidate = source.parameters.prefix;
            candidate.coded_width != prefix.coded_width
                || candidate.coded_height != prefix.coded_height
                || candidate.display_width != prefix.display_width
                || candidate.display_height != prefix.display_height
                || candidate.crop != prefix.crop
        });
        let request = hevc_output::Request {
            ids: [0; 3],
            width: grid.width,
            height: grid.height,
            columns: grid.columns,
            rows: grid.rows,
            frame_rate: grid.frame_rate,
            loop_across_tiles,
            uncropped,
        };
        let generated = request.generate(template_init.configuration.parameter_sets())?;
        let key = generated.coding_key()?;
        for (index, (init, source)) in sources.iter().enumerate() {
            if source.parameters.prefix.dependent_slices || source.parameters.pps.weighted_p
                || source.parameters.pps.weighted_b || source.parameters.pps.lists_modification
            {
                return Err(Error::Unsupported);
            }
            if index != template_index
                && request.generate(init.configuration.parameter_sets())?.coding_key()? != key
            {
                return Err(Error::Unsupported);
            }
        }
        let output_tiles = tile_locations(&generated.parameters)?;
        let mut placements = Vec::new();
        placements
            .try_reserve_exact(grid.cells.len())
            .map_err(|_| Error::Limit)?;
        let mut own_seams = true;
        for (index, (&cell, tile)) in grid.cells.iter().zip(output_tiles).enumerate() {
            let source_tile = sources
                .get(cell.feed)
                .and_then(|(_, source)| source.tiles.get(cell.tile))
                .ok_or(Error::Metadata)?;
            if source_tile.width != tile.width || source_tile.height != tile.height {
                return Err(Error::Unsupported);
            }
            let source = &sources[cell.feed].1;
            let source_columns = source.parameters.tile_columns.len();
            let column = index % grid.columns.len();
            let row = index / grid.columns.len();
            if column != 0 {
                let left = grid.cells[index - 1];
                own_seams &= !cell.tile.is_multiple_of(source_columns) &&
                    left.feed == cell.feed && left.tile.checked_add(1) == Some(cell.tile);
            }
            if row != 0 {
                let top = grid.cells[index - grid.columns.len()];
                own_seams &= cell.tile >= source_columns &&
                    top.feed == cell.feed && top.tile.checked_add(source_columns) == Some(cell.tile);
            }
            placements.push(Placement {
                cell,
                address: tile.address,
            });
        }
        if loop_across_tiles && !own_seams {
            return Err(Error::Metadata);
        }
        let mut hvcc = generated.hvcc()?;
        hvcc[21] = (hvcc[21] & !3) | (output_nal_prefix as u8 - 1);
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
            own_seams,
            output_nal_prefix,
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
        let mut trackers = [Tracker::default(); MAX_FEEDS];
        self.write_window_fragment(input, sequence, output, &mut trackers, None, true)
            .map(|(size, _, _)| size)
    }

    /// The caller owns a bounded window and fresh trackers; no reference state
    /// survives cancellation, rejection or a seek.
    pub(crate) fn write_window_fragment(
        &self,
        input: &[&[u8]],
        sequence: u32,
        output: &mut [u8],
        trackers: &mut [Tracker; MAX_FEEDS],
        previous_end: Option<i64>,
        standalone: bool,
    ) -> Result<(usize, i64, bool), Error> {
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
        if previous_end.is_some_and(|end| end != reference.decode_time) {
            return Err(Error::Timing);
        }
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
        let mut picture_parts = Vec::new();
        picture_parts
            .try_reserve_exact(pictures)
            .map_err(|_| Error::Limit)?;
        let mut output_bytes = 0usize;
        let mut can_gate_filter = self.own_seams;
        for (index, source_sample) in reference.samples.iter().enumerate() {
            let mut feed_slices = Vec::new();
            feed_slices
                .try_reserve_exact(self.sources.len())
                .map_err(|_| Error::Limit)?;
            let mut order: Option<Identity> = None;
            for (feed, (source, fragment)) in self.sources.iter().zip(&fragments).enumerate() {
                let incoming = &fragment.samples[index];
                match_timing(source_sample, self.sources[0].timescale, incoming, source.timescale)?;
                let (slices, leading_eos, trailing_eos) = source.slices(incoming)?;
                let first_slice = slices.first().ok_or(Error::Metadata)?;
                let picture = first_slice.1.prefix.picture.ok_or(Error::Metadata)?;
                if previous_end.is_none() && index == 0
                    && (!(16..=21).contains(&picture.nal_type) || incoming.flags & 0x1_0000 != 0)
                {
                    return Err(Error::Unsupported);
                }
                if slices.iter().any(|(_, slice)| {
                    slice.prefix.picture != Some(picture)
                        || slice.references != first_slice.1.references
                        || slice.prediction != first_slice.1.prediction
                        || slice.prefix.no_output_of_prior != first_slice.1.prefix.no_output_of_prior
                }) || feed_slices.first().is_some_and(|first: &Vec<(usize, Slice<'_>)>| {
                    first[0].1.prefix.picture != Some(picture)
                        || first[0].1.references != first_slice.1.references
                        || first[0].1.prediction != first_slice.1.prediction
                        || first[0].1.prefix.no_output_of_prior != first_slice.1.prefix.no_output_of_prior
                }) {
                    return Err(Error::Metadata);
                }
                let eos = trackers[feed].take_end_of_sequence();
                let position = trackers[feed].advance(
                    picture,
                    source.parameters.prefix.poc_lsb_bits,
                    previous_end.is_none() && index == 0 || eos || leading_eos,
                )?;
                if trailing_eos { trackers[feed].end_of_sequence(); }
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
                let slices = &feed_slices[feed];
                let index = slices.binary_search_by_key(&tile, |(index, _)| *index)
                    .map_err(|_| Error::Metadata)?;
                let slice = &slices[index].1;
                let pps = &self.sources[feed].parameters.pps;
                let gate = pps.tiles && pps.loop_across_tiles && pps.loop_across_slices &&
                    slice.loop_filter_bit.is_some();
                can_gate_filter &= gate;
                let filter = if self.output.pps.loop_across_tiles {
                    if !gate { return Err(Error::Metadata); }
                    Some(slice.loop_filter_across_slices == Some(true))
                } else { None };
                let nal = self.output.rewrite_tile(slice, placement.address, filter)?;
                output_bytes = output_bytes
                    .checked_add(nal.header.len() + nal.tail.len() + self.output_nal_prefix)
                    .ok_or(Error::Limit)?;
                if output_bytes > MAX_ATLAS_BYTES { return Err(Error::Limit); }
                if output_bytes > output.len() { return Err(Error::Capacity); }
                nals.push(nal);
            }
            picture_parts.push(nals);
        }
        if (standalone || self.output.pps.loop_across_tiles)
            && self.output.pps.loop_across_tiles != can_gate_filter {
            return Err(Error::Metadata);
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
        let last = reference.samples.last().ok_or(Error::Timing)?;
        let end = last.decode_time.checked_add(i64::from(last.duration)).ok_or(Error::Timing)?;
        let required = clear_cmaf::nal_parts_size(&samples, self.output_nal_prefix).ok_or(Error::Limit)?;
        if required > output.len() { return Err(Error::Capacity); }
        let size = clear_cmaf::write_nal_parts(
            self.track,
            sequence,
            reference.decode_time as u64,
            &samples,
            self.output_nal_prefix,
            output,
        )
        .ok_or(Error::Limit)?;
        Ok((size, end, can_gate_filter))
    }
}

type SampleSlices<'a> = (Vec<(usize, Slice<'a>)>, bool, bool);

impl Source {
    fn slices<'b>(&self, sample: &Sample<'b>) -> Result<SampleSlices<'b>, Error> {
        let mut slices = Vec::new();
        let mut leading_eos = false;
        let mut trailing_eos = false;
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
                    let expected = &self.parameter_sets[usize::from(header.unit_type - 32)];
                    if nal != expected.as_slice() {
                        let mut sets = self.parameter_sets.each_ref().map(Vec::as_slice);
                        sets[usize::from(header.unit_type - 32)] = nal;
                        let candidate = Parameters::parse(sets)?;
                        if hevc_output::parameter_identity(sets, &candidate, false)? != self.parameter_identity {
                            return Err(Error::Metadata);
                        }
                    }
                }
                36 => {
                    // EOS changes the next CRA POC epoch even though the
                    // reference does not copy non-VCL NALs into the atlas.
                    if slices.is_empty() { leading_eos = true; }
                    else { trailing_eos = true; }
                }
                35 | 37..=40 => {} // reference discards AUD/EOB/filler/SEI
                0..=31 => {
                    let slice = self.parameters.read_slice(nal)?;
                    let index = self.tiles.binary_search_by_key(&slice.prefix.address, |tile| tile.address)
                        .map_err(|_| Error::Metadata)?;
                    if slice.prefix.first != slices.is_empty() || slice.prefix.first != (index == 0)
                        || slices.last().is_some_and(|(previous, _)| *previous >= index)
                        || slice.prefix.picture.is_none() || slice.entry_points != 0
                    {
                        return Err(Error::Unsupported);
                    }
                    slices.push((index, slice));
                }
                _ => return Err(Error::Unsupported),
            }
        }
        if slices.is_empty() {
            return Err(Error::Metadata);
        }
        Ok((slices, leading_eos, trailing_eos))
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

fn match_timing(a: &Sample<'_>, a_scale: u32, b: &Sample<'_>, b_scale: u32) -> Result<(), Error> {
    if (a.flags | b.flags) & 0xf000_0000 != 0 {
        return Err(Error::Metadata);
    }
    if a_scale == 0 || b_scale == 0 || (a.flags ^ b.flags) & 0x0fff_0000 != 0 {
        return Err(Error::Timing);
    }
    // Exact rational clock comparison. The i64-by-u32 product fits i128;
    // never round timestamps or alter the first source's output clock.
    let equal = |left: i64, right: i64| {
        i128::from(left) * i128::from(b_scale) == i128::from(right) * i128::from(a_scale)
    };
    if !equal(i64::from(a.duration), i64::from(b.duration))
        || !equal(a.decode_time, b.decode_time)
        || !equal(a.composition_offset, b.composition_offset)
        || !equal(a.presentation_time, b.presentation_time)
    {
        return Err(Error::Timing);
    }
    Ok(())
}

fn require_bounded_bytes(chunks: &[&[u8]]) -> Result<(), Error> {
    let total = chunks
        .iter()
        .try_fold(0usize, |total, bytes| total.checked_add(bytes.len()))
        .ok_or(Error::Limit)?;
    if total > MAX_ATLAS_BYTES {
        return Err(Error::Limit);
    }
    Ok(())
}
