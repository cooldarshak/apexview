//! Parameter-set syntax required to parse supported HEVC slices.
use crate::bmff::Error;
use crate::hevc_prefix::{Bits, StreamPrefix};
use crate::hevc_references::{LongTerm, ShortTerm};
use std::ops::Range;

pub struct Parameters {
    pub prefix: StreamPrefix,
    pub tile_columns: Vec<u32>,
    pub tile_rows: Vec<u32>,
    pub(crate) vps_hrd_schedules: Vec<Range<usize>>,
    pub(crate) sps: Sps,
    pub(crate) pps: Pps,
}

impl Parameters {
    pub fn parse(nals: [&[u8]; 3]) -> Result<Self, Error> {
        let (prefix, mut vps, mut sps, mut pps) = StreamPrefix::prefixes(nals)?;
        let vps_hrd_schedules = validate_vps(&mut vps, prefix.vps_sub_layers_minus_one)?;
        vps.finish_rbsp()?;
        let parsed_sps = Sps::read(&mut sps, &prefix)?;
        sps.finish_rbsp()?;
        let (parsed_pps, tile_columns, tile_rows) = Pps::read(&mut pps, &prefix)?;
        pps.finish_rbsp()?;
        Ok(Self {
            prefix,
            vps_hrd_schedules,
            sps: parsed_sps,
            pps: parsed_pps,
            tile_columns,
            tile_rows,
        })
    }
}

#[derive(PartialEq, Eq)]
pub(crate) struct Sps {
    pub sao: bool,
    pub temporal_mvp: bool,
    pub short: Vec<ShortTerm>,
    pub short_start: usize,
    pub short_end: usize,
    pub long: Option<Vec<LongTerm>>,
    pub vui_start: usize,
    pub vui_end: usize,
    pub vui: Option<VuiIdentity>,
}

impl Sps {
    fn read(bits: &mut Bits<'_>, prefix: &StreamPrefix) -> Result<Self, Error> {
        bits.ue(3)?; // log2_min_luma_transform_block_size_minus2
        bits.ue(3)?; // log2_diff_max_min_luma_transform_block_size
        bits.ue(4)?; // max_transform_hierarchy_depth_inter
        bits.ue(4)?; // max_transform_hierarchy_depth_intra
        if bits.flag()? && bits.flag()? {
            return Err(Error::Unsupported); // The reference rewriter rejects custom scaling lists.
        }
        bits.flag()?; // amp_enabled_flag
        let sao = bits.flag()?;
        if bits.flag()? {
            return Err(Error::Unsupported); // PCM is outside the supported rewriting profile.
        }
        let short_start = bits.consumed;
        let count = bits.ue(64)? as usize;
        let mut short = Vec::new();
        short.try_reserve_exact(count).map_err(|_| Error::Limit)?;
        for _ in 0..count {
            short.push(ShortTerm::read(bits, &short, false)?);
        }
        let short_end = bits.consumed;
        let long = if bits.flag()? {
            let count = bits.ue(32)? as usize;
            let mut values = Vec::new();
            values.try_reserve_exact(count).map_err(|_| Error::Limit)?;
            for _ in 0..count {
                values.push(LongTerm::read(bits, prefix.poc_lsb_bits)?);
            }
            Some(values)
        } else {
            None
        };
        let temporal_mvp = bits.flag()?;
        bits.flag()?; // strong_intra_smoothing_enabled_flag
        let vui_start = bits.consumed;
        let vui = if bits.flag()? {
            Some(read_vui(bits, prefix.max_sub_layers - 1)?)
        } else { None };
        let vui_end = bits.consumed;
        if bits.flag()? {
            return Err(Error::Unsupported);
        }
        Ok(Self {
            sao,
            temporal_mvp,
            short,
            short_start,
            short_end,
            long,
            vui_start,
            vui_end,
            vui,
        })
    }
}

#[derive(PartialEq, Eq)]
pub(crate) struct Pps {
    pub cabac_init: bool,
    pub l0: u32,
    pub l1: u32,
    pub initial_qp: i32,
    pub slice_chroma_offsets: bool,
    pub weighted_p: bool,
    pub weighted_b: bool,
    pub tiles: bool,
    pub loop_across_slices: bool,
    pub override_deblock: bool,
    pub deblock_disabled: bool,
    pub lists_modification: bool,
    pub loop_across_tiles: bool,
    pub tile_start: usize,
    pub tile_end: usize,
}

impl Pps {
    fn read(
        bits: &mut Bits<'_>,
        prefix: &StreamPrefix,
    ) -> Result<(Self, Vec<u32>, Vec<u32>), Error> {
        bits.flag()?; // sign_data_hiding_enabled_flag
        let cabac_init = bits.flag()?;
        let l0 = 1 + bits.ue(14)?;
        let l1 = 1 + bits.ue(14)?;
        let initial_qp = 26 + bits.se(-26 - 6 * (prefix.bit_depth_luma as i32 - 8), 25)?;
        bits.read(2)?; // constrained_intra_pred_flag and transform_skip_enabled_flag
        if bits.flag()? {
            bits.ue(6)?;
        }
        bits.se(-12, 12)?;
        bits.se(-12, 12)?;
        let slice_chroma_offsets = bits.flag()?;
        let weighted_p = bits.flag()?;
        let weighted_b = bits.flag()?;
        bits.flag()?; // transquant_bypass_enabled_flag

        let tile_start = bits.consumed;
        let tiles = bits.flag()?;
        if bits.flag()? {
            return Err(Error::Unsupported); // WPP is not rewritable by the reference.
        }
        let mut loop_across_tiles = false;
        let (columns, rows) = if tiles {
            let columns = 1 + bits.ue(1023)?;
            let rows = 1 + bits.ue(1023)?;
            let uniform = bits.flag()?;
            let columns = partition(bits, prefix.width_in_ctbs, columns, uniform)?;
            let rows = partition(bits, prefix.height_in_ctbs, rows, uniform)?;
            loop_across_tiles = bits.flag()?;
            (columns, rows)
        } else {
            (vec![prefix.width_in_ctbs], vec![prefix.height_in_ctbs])
        };
        let tile_end = bits.consumed;
        let loop_across_slices = bits.flag()?;
        let mut override_deblock = false;
        let mut deblock_disabled = false;
        if bits.flag()? {
            override_deblock = bits.flag()?;
            deblock_disabled = bits.flag()?;
            if !deblock_disabled {
                bits.se(-6, 6)?;
                bits.se(-6, 6)?;
            }
        }
        if bits.flag()? {
            return Err(Error::Unsupported); // Custom PPS scaling-list data.
        }
        let lists_modification = bits.flag()?;
        bits.ue(6)?; // log2_parallel_merge_level_minus2
        if bits.flag()? || bits.flag()? {
            return Err(Error::Unsupported); // Slice and PPS extensions.
        }
        Ok((
            Self {
                cabac_init,
                l0,
                l1,
                initial_qp,
                slice_chroma_offsets,
                weighted_p,
                weighted_b,
                tiles,
                loop_across_slices,
                override_deblock,
                deblock_disabled,
                lists_modification,
                loop_across_tiles,
                tile_start,
                tile_end,
            },
            columns,
            rows,
        ))
    }
}

fn partition(
    bits: &mut Bits<'_>,
    total: u32,
    count: u32,
    uniform: bool,
) -> Result<Vec<u32>, Error> {
    if count > total {
        return Err(Error::Metadata);
    }
    let mut parts = Vec::new();
    parts
        .try_reserve_exact(count as usize)
        .map_err(|_| Error::Limit)?;
    let mut consumed = 0;
    for i in 1..=count {
        let size = if uniform {
            i * total / count - consumed
        } else if i == count {
            total - consumed
        } else {
            1 + bits.ue(32767)?
        };
        if size == 0 || size > total - consumed {
            return Err(Error::Metadata);
        }
        consumed += size;
        parts.push(size);
    }
    Ok(parts)
}

fn timing(bits: &mut Bits<'_>) -> Result<(), Error> {
    let tick = bits.read(32)?;
    let scale = bits.read(32)?;
    if tick == 0 || scale == 0 {
        return Err(Error::Timing);
    }
    if bits.flag()? {
        bits.ue(32767)?;
    }
    Ok(())
}

fn validate_vps(bits: &mut Bits<'_>, sublayers: u32) -> Result<Vec<Range<usize>>, Error> {
    let mut schedules = Vec::new();
    bits.skip_profile_tier_level(sublayers)?;
    let first = if bits.flag()? { 0 } else { sublayers };
    for _ in first..=sublayers {
        bits.ue(32)?;
        bits.ue(32)?;
        bits.ue(1024)?;
    }
    let layer_id = bits.read(6)?;
    let sets = bits.ue(1023)?;
    for _ in 0..sets * (layer_id + 1) {
        bits.flag()?;
    }
    if bits.flag()? {
        timing(bits)?;
        let count = bits.ue(1024)?;
        let mut common = None;
        for index in 0..count {
            bits.ue(sets)?;
            if index == 0 || bits.flag()? {
                common = Some(HrdCommon::read(bits)?);
            }
            let start = bits.consumed;
            common
                .ok_or(Error::Metadata)?
                .validate_sublayers(bits, sublayers)?;
            schedules.try_reserve(1).map_err(|_| Error::Limit)?;
            schedules.push(start..bits.consumed);
        }
    }
    if bits.flag()? {
        return Err(Error::Unsupported);
    }
    Ok(schedules)
}

/// Same parsed VUI identity as the JVM source inspector. The retained source
/// template is unchanged; unmodelled presentation-only flags are still parsed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct VuiIdentity {
    aspect: Option<u32>,
    sar: Option<(u32, u32)>,
    video_signal: Option<u32>,
    colour: Option<u32>,
    timing: Option<(u32, u32)>,
    hrd: bool,
    restriction: bool,
}

fn read_vui(bits: &mut Bits<'_>, sublayers: u32) -> Result<VuiIdentity, Error> {
    let aspect = if bits.flag()? { Some(bits.read(8)?) } else { None };
    let sar = if aspect == Some(255) {
        let (width, height) = (bits.read(16)?, bits.read(16)?);
        if width == 0 || height == 0 {
            return Err(Error::Metadata);
        }
        Some((width, height))
    } else { None };
    if bits.flag()? {
        bits.flag()?;
    }
    let (video_signal, colour) = if bits.flag()? {
        let signal = bits.read(4)?;
        let colour = if bits.flag()? { Some(bits.read(24)?) } else { None };
        (Some(signal), colour)
    } else { (None, None) };
    if bits.flag()? {
        bits.ue(5)?;
        bits.ue(5)?;
    }
    bits.read(3)?; // Neutral chroma, field sequence and frame field info.
    if bits.flag()? {
        for _ in 0..4 {
            bits.ue(32768)?;
        }
    }
    let mut hrd = false;
    let timing = if bits.flag()? {
        let tick = bits.read(32)?;
        let scale = bits.read(32)?;
        if tick == 0 || scale == 0 { return Err(Error::Metadata); }
        if bits.flag()? { bits.ue(32767)?; }
        hrd = bits.flag()?;
        if hrd { HrdCommon::read(bits)?.validate_sublayers(bits, sublayers)?; }
        Some((tick, scale))
    } else { None };
    let restriction = bits.flag()?;
    if restriction {
        bits.read(3)?;
        bits.ue(32767)?;
        for _ in 0..4 {
            bits.ue(16)?;
        }
    }
    Ok(VuiIdentity { aspect, sar, video_signal, colour, timing, hrd, restriction })
}

#[derive(Clone, Copy)]
struct HrdCommon {
    nal: bool,
    vcl: bool,
    subpicture: bool,
}

impl HrdCommon {
    fn read(bits: &mut Bits<'_>) -> Result<Self, Error> {
        let (nal, vcl) = (bits.flag()?, bits.flag()?);
        let subpicture = (nal || vcl) && bits.flag()?;
        if nal || vcl {
            if subpicture {
                bits.read(19)?;
            }
            bits.read(if subpicture { 27 } else { 23 })?;
        }
        Ok(Self {
            nal,
            vcl,
            subpicture,
        })
    }

    fn validate_sublayers(self, bits: &mut Bits<'_>, sublayers: u32) -> Result<(), Error> {
        for _ in 0..=sublayers {
            let fixed_rate = bits.flag()? || bits.flag()?;
            let low_delay = if fixed_rate {
                bits.ue(32767)?;
                false
            } else {
                bits.flag()?
            };
            let count = if low_delay { 1 } else { 1 + bits.ue(31)? };
            for enabled in [self.nal, self.vcl] {
                if enabled {
                    for _ in 0..count {
                        for _ in 0..if self.subpicture { 4 } else { 2 } {
                            bits.ue(i32::MAX as u32)?;
                        }
                        bits.flag()?;
                    }
                }
            }
        }
        Ok(())
    }
}
