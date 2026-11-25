//! Bounded early HEVC syntax shared by stream and picture identity checks.
//! RPS, full slice validation and output parameter generation are separate work.
use crate::bmff::Error;
use crate::hevc_nal;

const MAX_PREFIX_BYTES: usize = 8192;

struct Bits<'a> {
    ebsp: &'a [u8],
    bytes: usize,
    consumed: usize,
    current: u8,
    pending: u8,
    zeros: u8,
}

impl<'a> Bits<'a> {
    fn new(nal: &'a [u8], expected_type: u8) -> Result<Self, Error> {
        let header = hevc_nal::header(nal)?;
        if header.unit_type != expected_type || header.layer_id != 0 {
            return Err(Error::Nal);
        }
        Ok(Self {
            ebsp: &nal[2..],
            bytes: 0,
            consumed: 0,
            current: 0,
            pending: 0,
            zeros: 0,
        })
    }

    fn next_byte(&mut self) -> Result<u8, Error> {
        if self.bytes == MAX_PREFIX_BYTES {
            return Err(Error::Limit);
        }
        let value = *self.ebsp.get(self.bytes).ok_or(Error::Extent)?;
        self.bytes += 1;
        if self.zeros == 2 && value == 3 {
            self.zeros = 0;
            if *self.ebsp.get(self.bytes).ok_or(Error::Extent)? > 3 {
                return Err(Error::Nal);
            }
            return self.next_byte();
        }
        self.zeros = if value == 0 {
            (self.zeros + 1).min(2)
        } else {
            0
        };
        Ok(value)
    }

    fn read(&mut self, count: u32) -> Result<u32, Error> {
        let mut value = 0u32;
        for _ in 0..count {
            if self.pending == 0 {
                self.current = self.next_byte()?;
                self.pending = 8;
            }
            self.pending -= 1;
            self.consumed += 1;
            value = (value << 1) | u32::from((self.current >> self.pending) & 1);
        }
        Ok(value)
    }

    fn flag(&mut self) -> Result<bool, Error> {
        Ok(self.read(1)? == 1)
    }

    fn ue(&mut self, max: u32) -> Result<u32, Error> {
        let mut zeros = 0;
        while !self.flag()? {
            zeros += 1;
            if zeros > 31 || (1u32 << zeros) - 1 > max {
                return Err(Error::Nal);
            }
        }
        let value = (1u32 << zeros) - 1 + self.read(zeros)?;
        if value > max {
            Err(Error::Nal)
        } else {
            Ok(value)
        }
    }

    fn skip_profile_tier_level(&mut self, sublayers_minus_one: u32) -> Result<(), Error> {
        // Both profile_tier_level general fields and a signalled sub-layer
        // profile carry 2+1+5+32+48 bits. Each level contributes 8 more.
        self.read(32)?;
        self.read(32)?;
        self.read(32)?;
        let flags = self.read(2 * sublayers_minus_one)?;
        if sublayers_minus_one != 0 {
            self.read(2 * (8 - sublayers_minus_one))?;
        }
        for i in (0..sublayers_minus_one).rev() {
            if flags & (2 << (2 * i)) != 0 {
                self.read(32)?;
                self.read(32)?;
                self.read(24)?;
            }
            if flags & (1 << (2 * i)) != 0 {
                self.read(8)?;
            }
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug)]
pub struct StreamPrefix {
    pub coded_width: u32,
    pub coded_height: u32,
    pub display_width: u32,
    pub display_height: u32,
    pub width_in_ctbs: u32,
    pub height_in_ctbs: u32,
    pub max_sub_layers: u32,
    pub pps_id: u32,
    separate_colour_plane: bool,
    pub poc_lsb_bits: u32,
    dependent_slices: bool,
    output_flag_present: bool,
    extra_header_bits: u32,
}

impl StreamPrefix {
    pub fn parse([vps, sps, pps]: [&[u8]; 3]) -> Result<Self, Error> {
        let mut bits = Bits::new(vps, 32)?;
        let vps_id = bits.read(4)?;
        bits.read(2)?; // base_layer_internal_flag and base_layer_available_flag
        let layers_minus_one = bits.read(6)?;
        let vps_sub_layers = bits.read(3)?;
        bits.flag()?;
        if layers_minus_one != 0 || vps_sub_layers > 6 || bits.read(16)? != 0xffff {
            return Err(Error::Unsupported);
        }

        let mut bits = Bits::new(sps, 33)?;
        if bits.read(4)? != vps_id {
            return Err(Error::Metadata);
        }
        let sub_layers = bits.read(3)?;
        if sub_layers > vps_sub_layers {
            return Err(Error::Metadata);
        }
        bits.flag()?; // sps_temporal_id_nesting_flag
        bits.skip_profile_tier_level(sub_layers)?;
        let sps_id = bits.ue(15)?;
        let chroma = bits.ue(3)?;
        let separate_colour_plane = chroma == 3 && bits.flag()?;
        let width = bits.ue(32768)?;
        let height = bits.ue(32768)?;
        let (mut display_width, mut display_height) = (width, height);
        if bits.flag()? {
            let crop_width = bits.ue(32768)? + bits.ue(32768)?;
            let crop_height = bits.ue(32768)? + bits.ue(32768)?;
            let x = if matches!(chroma, 1 | 2) { 2 } else { 1 };
            let y = if chroma == 1 { 2 } else { 1 };
            display_width = width.checked_sub(crop_width * x).ok_or(Error::Metadata)?;
            display_height = height.checked_sub(crop_height * y).ok_or(Error::Metadata)?;
        }
        if display_width == 0 || display_height == 0 {
            return Err(Error::Metadata);
        }
        bits.ue(8)?; // bit_depth_luma_minus8
        bits.ue(8)?; // bit_depth_chroma_minus8
        let poc_lsb_bits = 4 + bits.ue(12)?;
        let first_layer = if bits.flag()? { 0 } else { sub_layers };
        for _ in first_layer..=sub_layers {
            bits.ue(32)?; // sps_max_dec_pic_buffering_minus1
            bits.ue(32)?; // sps_max_num_reorder_pics
            bits.ue(1024)?; // sps_max_latency_increase_plus1
        }
        let log2_ctb = 3 + bits.ue(3)? + bits.ue(3)?;
        if log2_ctb > 6 {
            return Err(Error::Unsupported);
        }
        let ctb = 1 << log2_ctb;

        let mut bits = Bits::new(pps, 34)?;
        let pps_id = bits.ue(63)?;
        if bits.ue(15)? != sps_id {
            return Err(Error::Metadata);
        }
        Ok(Self {
            coded_width: width,
            coded_height: height,
            display_width,
            display_height,
            width_in_ctbs: width.div_ceil(ctb),
            height_in_ctbs: height.div_ceil(ctb),
            max_sub_layers: sub_layers + 1,
            pps_id,
            separate_colour_plane,
            poc_lsb_bits,
            dependent_slices: bits.flag()?,
            output_flag_present: bits.flag()?,
            extra_header_bits: bits.read(3)?,
        })
    }

    pub fn slice(&self, nal: &[u8]) -> Result<SlicePrefix, Error> {
        let header = hevc_nal::header(nal)?;
        if header.unit_type > 21 || u32::from(header.temporal_id_plus_one) > self.max_sub_layers {
            return Err(Error::Unsupported);
        }
        let mut bits = Bits::new(nal, header.unit_type)?;
        let first = bits.flag()?;
        let no_output_of_prior = (16..=21).contains(&header.unit_type) && bits.flag()?;
        if bits.ue(63)? != self.pps_id {
            return Err(Error::Metadata);
        }
        let dependent = !first && self.dependent_slices && bits.flag()?;
        let count = self.width_in_ctbs * self.height_in_ctbs;
        let width = u32::BITS - (count - 1).leading_zeros();
        let address = if first { 0 } else { bits.read(width)? };
        if address >= count {
            return Err(Error::Metadata);
        }
        let address_end_bit = bits.consumed;
        let mut picture = None;
        if !dependent {
            bits.read(self.extra_header_bits)?;
            let slice_type = bits.ue(2)?;
            if self.output_flag_present {
                bits.flag()?;
            }
            if self.separate_colour_plane && bits.read(2)? > 2 {
                return Err(Error::Nal);
            }
            let poc_lsb = if matches!(header.unit_type, 19 | 20) {
                None
            } else {
                Some(bits.read(self.poc_lsb_bits)?)
            };
            picture = Some(PicturePrefix {
                nal_type: header.unit_type,
                slice_type,
                temporal_id_plus_one: header.temporal_id_plus_one,
                poc_lsb,
            });
        }
        Ok(SlicePrefix {
            first,
            address,
            address_end_bit,
            no_output_of_prior,
            picture,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PicturePrefix {
    pub nal_type: u8,
    pub slice_type: u32,
    pub temporal_id_plus_one: u8,
    pub poc_lsb: Option<u32>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SlicePrefix {
    pub first: bool,
    pub address: u32,
    pub address_end_bit: usize,
    pub no_output_of_prior: bool,
    pub picture: Option<PicturePrefix>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rbsp_reader_preserves_logical_offsets_and_checks_escape_bytes() {
        let valid = [0x42, 1, 0, 0, 3, 1, 2];
        let mut bits = Bits::new(&valid, 33).unwrap();
        assert_eq!(bits.read(24), Ok(1));
        assert_eq!(bits.consumed, 24);
        assert_eq!(bits.read(8), Ok(2));
        assert_eq!(bits.read(1), Err(Error::Extent));

        let mut bits = Bits::new(&[0x42, 1, 0, 0, 3, 4], 33).unwrap();
        assert_eq!(bits.read(24), Err(Error::Nal));
        let mut bits = Bits::new(&[0x42, 1, 0, 0, 3], 33).unwrap();
        assert_eq!(bits.read(24), Err(Error::Extent));
    }
}
