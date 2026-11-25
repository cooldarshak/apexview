//! Native bounded slice parsing and rewrite offsets for the supported profile.
use crate::bmff::Error;
use crate::clear_slice::{rewrite_parts_with_filter, rewrite_into_slab, Parts, SlabPart, Syntax};
use crate::hevc_parameters::Parameters;
use crate::hevc_prefix::{Bits, SlicePrefix};
use crate::hevc_references::{address_bits, References};

/// Prediction fields which must match across independent cells, even if
/// the expanded RPS and container clocks happen to match.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PredictionIdentity {
    l0: u32,
    l1: u32,
    mvd_l1_zero: Option<bool>,
    cabac_init: Option<bool>,
    merge_candidates_minus: u32,
}

pub struct Slice<'a> {
    nal: &'a [u8],
    pub prefix: SlicePrefix,
    pub references: References,
    pub prediction: Option<PredictionIdentity>,
    pub loop_filter_bit: Option<usize>,
    pub loop_filter_across_slices: Option<bool>,
    pub entry_points: u32,
    entry_start: usize,
    entry_end: usize,
    alignment: usize,
    header_bytes: usize,
}

impl Parameters {
    pub fn read_slice<'a>(&self, nal: &'a [u8]) -> Result<Slice<'a>, Error> {
        let (prefix, mut bits) = self.prefix.slice_reader(nal)?;
        let mut references = References::default();
        let mut prediction = None;
        let mut loop_filter_bit = None;
        let mut loop_filter_across_slices = None;
        let mut entry_points = 0;
        let mut entry_start = bits.consumed;
        let mut entry_end = entry_start;
        if let Some(picture) = prefix.picture {
            let idr = picture.poc_lsb.is_none();
            if !idr {
                references = References::read(
                    &mut bits,
                    &self.sps.short,
                    self.sps.long.as_deref(),
                    self.prefix.poc_lsb_bits,
                )?;
            }
            let tmvp = self.sps.temporal_mvp && !idr && bits.flag()?;
            let sao_luma = self.sps.sao && bits.flag()?;
            let chroma = self.prefix.chroma_array_type != 0;
            let sao_chroma = self.sps.sao && chroma && bits.flag()?;
            if picture.slice_type != 2 {
                prediction = Some(self.inter_prediction(
                    &mut bits,
                    picture.slice_type == 0,
                    tmvp,
                    references.used_count(),
                )?);
            }
            let initial_qp = self.pps.initial_qp;
            bits.se(
                -6 * (self.prefix.bit_depth_luma as i32 - 8) - initial_qp,
                51 - initial_qp,
            )?;
            if self.pps.slice_chroma_offsets {
                bits.se(-12, 12)?;
                bits.se(-12, 12)?;
            }
            let mut deblock_disabled = self.pps.deblock_disabled;
            if self.pps.override_deblock && bits.flag()? {
                deblock_disabled = bits.flag()?;
                if !deblock_disabled {
                    bits.se(-6, 6)?;
                    bits.se(-6, 6)?;
                }
            }
            if self.pps.loop_across_slices && (sao_luma || sao_chroma || !deblock_disabled) {
                loop_filter_bit = Some(bits.consumed);
                loop_filter_across_slices = Some(bits.flag()?);
            }
            entry_start = bits.consumed;
            if self.pps.tiles {
                entry_points = bits.ue(65535)?;
                if entry_points != 0 {
                    let length = 1 + bits.ue(31)?;
                    for _ in 0..entry_points {
                        bits.read(length)?;
                    }
                }
            }
            entry_end = bits.consumed;
        }
        let alignment = bits.consumed;
        if !bits.flag()? {
            return Err(Error::Nal);
        }
        while !bits.consumed.is_multiple_of(8) {
            if bits.flag()? {
                return Err(Error::Nal);
            }
        }
        Ok(Slice {
            nal,
            prefix,
            references,
            prediction,
            loop_filter_bit,
            loop_filter_across_slices,
            entry_points,
            entry_start,
            entry_end,
            alignment,
            header_bytes: bits.consumed / 8,
        })
    }

    fn inter_prediction(
        &self,
        bits: &mut Bits<'_>,
        b_picture: bool,
        tmvp: bool,
        references: u32,
    ) -> Result<PredictionIdentity, Error> {
        let override_active = bits.flag()?;
        let l0 = if override_active {
            1 + bits.ue(14)?
        } else {
            self.pps.l0
        };
        let l1 = if !b_picture {
            0
        } else if override_active {
            1 + bits.ue(14)?
        } else {
            self.pps.l1
        };
        if self.pps.lists_modification && references > 1 {
            let width = address_bits(references);
            for count in [l0, l1] {
                if count != 0 && bits.flag()? {
                    for _ in 0..count {
                        if bits.read(width)? >= references {
                            return Err(Error::Nal);
                        }
                    }
                }
            }
        }
        let mvd_l1_zero = if b_picture { Some(bits.flag()?) } else { None };
        let cabac_init = if self.pps.cabac_init { Some(bits.flag()?) } else { None };
        if tmvp {
            let from_l0 = !b_picture || bits.flag()?;
            let count = if from_l0 { l0 } else { l1 };
            if count > 1 {
                bits.ue(count - 1)?;
            }
        }
        if (b_picture && self.pps.weighted_b) || (!b_picture && self.pps.weighted_p) {
            prediction_weights(bits, l0, l1, self.prefix.chroma_array_type != 0)?;
        }
        let merge_candidates_minus = bits.ue(4)?;
        Ok(PredictionIdentity { l0, l1, mvd_l1_zero, cabac_init, merge_candidates_minus })
    }

    pub fn rewrite_tile<'a>(
        &self,
        slice: &Slice<'a>,
        destination: u32,
        loop_filter: Option<bool>,
    ) -> Result<Parts<'a>, Error> {
        let syntax = self.rewrite_syntax(slice, destination)?;
        let loop_filter = match loop_filter {
            Some(value) => Some((slice.loop_filter_bit.ok_or(Error::Metadata)?, value)),
            None => None,
        };
        rewrite_parts_with_filter(slice.nal, syntax, loop_filter).ok_or(Error::Nal)
    }

    pub(crate) fn rewrite_tile_into_slab<'a>(
        &self, slice: &Slice<'a>, destination: u32, loop_filter: Option<bool>,
        slab: &mut Vec<u8>, ceiling: usize,
    ) -> Result<SlabPart<'a>, Error> {
        let syntax = self.rewrite_syntax(slice, destination)?;
        let loop_filter = match loop_filter {
            Some(value) => Some((slice.loop_filter_bit.ok_or(Error::Metadata)?, value)),
            None => None,
        };
        rewrite_into_slab(slice.nal, syntax, loop_filter, slab, ceiling)
    }

    /// Maps one validated independently coded slice onto an output CTB cell.
    /// Cross-feed coding and picture-compatibility checks remain a caller contract.
    pub fn rewrite_syntax(&self, slice: &Slice<'_>, destination: u32) -> Result<Syntax, Error> {
        let picture_ctbs = self.prefix.width_in_ctbs * self.prefix.height_in_ctbs;
        if destination >= picture_ctbs || slice.prefix.picture.is_none() || slice.entry_points != 0
        {
            return Err(Error::Unsupported);
        }
        Ok(Syntax {
            address_end: slice.prefix.address_end_bit,
            entry_start: slice.entry_start,
            entry_end: slice.entry_end,
            alignment: slice.alignment,
            header_bytes: slice.header_bytes,
            destination,
            address_bits: address_bits(picture_ctbs) as usize,
            pps: self.prefix.pps_id,
            dependent_flag_present: self.prefix.dependent_slices,
            tiles: self.pps.tiles,
            no_output: slice.prefix.no_output_of_prior,
        })
    }
}

fn prediction_weights(bits: &mut Bits<'_>, l0: u32, l1: u32, chroma: bool) -> Result<(), Error> {
    let luma_denom = bits.ue(7)? as i32;
    if chroma {
        let denom = luma_denom + bits.se(-7, 7)?;
        if !(0..=7).contains(&denom) {
            return Err(Error::Nal);
        }
    }
    for count in [l0, l1] {
        let luma_flags = bits.read(count)?;
        let chroma_flags = if chroma { bits.read(count)? } else { 0 };
        for index in (0..count).rev() {
            if luma_flags & (1 << index) != 0 {
                bits.se(-128, 127)?;
                bits.se(-128, 127)?;
            }
            if chroma_flags & (1 << index) != 0 {
                for _ in 0..2 {
                    bits.se(-128, 127)?;
                    bits.se(-512, 511)?;
                }
            }
        }
    }
    Ok(())
}
