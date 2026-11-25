//! Syntax-aware native VPS/SPS/PPS generation for an independently coded atlas.
use crate::bmff::Error;
use crate::clear_slice::Writer;
use crate::hevc_parameters::Parameters;
use crate::hevc_prefix::Bits;
use std::ops::Range;

const MAX_PARAMETER_RBSP: usize = 8192;
const VPS_LEVEL: usize = 120;
const SPS_LEVEL: usize = 96;

pub struct Request<'a> {
    pub ids: [u32; 3],
    pub width: u32,
    pub height: u32,
    pub columns: &'a [u32],
    pub rows: &'a [u32],
    pub frame_rate: (u32, u32),
    pub loop_across_tiles: bool,
    pub uncropped: bool,
}

pub struct Output {
    pub nals: [Vec<u8>; 3],
    pub parameters: Parameters,
}

struct Level {
    id: u8,
    samples: u64,
    samples_per_second: u64,
    slices: usize,
    rows: usize,
    columns: usize,
}

// General level limits used by the in-tree JVM writer.
const LEVELS: [Level; 13] = [
    level(30, 36_864, 552_960, 16, 1, 1),
    level(60, 122_880, 3_686_400, 16, 1, 1),
    level(63, 245_760, 7_372_800, 20, 1, 1),
    level(90, 552_960, 16_588_800, 30, 2, 2),
    level(93, 983_040, 33_177_600, 40, 3, 3),
    level(120, 2_228_224, 66_846_720, 75, 5, 5),
    level(123, 2_228_224, 133_693_440, 75, 5, 5),
    level(150, 8_912_896, 267_386_880, 200, 11, 10),
    level(153, 8_912_896, 534_773_760, 200, 11, 10),
    level(156, 8_912_896, 1_069_547_520, 200, 11, 10),
    level(180, 35_651_584, 1_069_547_520, 600, 22, 20),
    level(183, 35_651_584, 2_139_095_040, 600, 22, 20),
    level(186, 35_651_584, 4_278_190_080, 600, 22, 20),
];

const fn level(
    id: u8,
    samples: u64,
    samples_per_second: u64,
    slices: usize,
    rows: usize,
    columns: usize,
) -> Level {
    Level {
        id,
        samples,
        samples_per_second,
        slices,
        rows,
        columns,
    }
}

impl Request<'_> {
    pub fn generate(&self, nals: [&[u8]; 3]) -> Result<Output, Error> {
        let [vps_id, sps_id, pps_id] = self.ids;
        if vps_id > 15
            || sps_id > 15
            || pps_id > 63
            || !(1..=32768).contains(&self.width)
            || !(1..=32768).contains(&self.height)
            || self.frame_rate.0 == 0
            || self.frame_rate.1 == 0
        {
            return Err(Error::Metadata);
        }

        let source = Parameters::parse(nals)?;
        let ctb = 1 << source.prefix.log2_ctb;
        if !valid_partition(self.columns, self.width.div_ceil(ctb))
            || !valid_partition(self.rows, self.height.div_ceil(ctb))
        {
            return Err(Error::Metadata);
        }
        let [vps, sps, pps] = nals;
        let source_vps = Bits::read_rbsp(vps, 32)?;
        let source_sps = Bits::read_rbsp(sps, 33)?;
        let source_pps = Bits::read_rbsp(pps, 34)?;
        let samples = u64::from(self.width) * u64::from(self.height);
        let rate = u128::from(samples) * u128::from(self.frame_rate.0);
        let required = LEVELS
            .iter()
            .find(|limit| {
                samples <= limit.samples
                    && rate <= u128::from(limit.samples_per_second) * u128::from(self.frame_rate.1)
                    && self.columns.len() <= limit.columns
                    && self.rows.len() <= limit.rows
                    && self.columns.len() * self.rows.len() <= limit.slices
            })
            .ok_or(Error::Unsupported)?;
        let output_level = required
            .id
            .max(source_vps[VPS_LEVEL / 8])
            .max(source_sps[SPS_LEVEL / 8]);
        if !LEVELS.iter().any(|limit| limit.id == output_level) {
            return Err(Error::Unsupported);
        }

        let new_vps = splice(vps, &source_vps, |w| {
            w.replace(0..4, |out| out.bits(vps_id, 4))?;
            w.replace(VPS_LEVEL..VPS_LEVEL + 8, |out| {
                out.bits(u32::from(output_level), 8)
            })
        })?;

        let mut sps_reader = Bits::new(sps, 33)?;
        sps_reader.read(8)?;
        sps_reader.skip_profile_tier_level(source.prefix.max_sub_layers - 1)?;
        let id = unsigned_range(&mut sps_reader, 15)?;
        if sps_reader.ue(3)? == 3 {
            sps_reader.flag()?;
        }
        let width = unsigned_range(&mut sps_reader, 32768)?;
        let height = unsigned_range(&mut sps_reader, 32768)?;
        let crop = sps_reader.consumed;
        if sps_reader.flag()? {
            for _ in 0..4 {
                sps_reader.ue(32768)?;
            }
        }
        let new_sps = splice(sps, &source_sps, |w| {
            w.replace(0..4, |out| out.bits(vps_id, 4))?;
            w.replace(SPS_LEVEL..SPS_LEVEL + 8, |out| {
                out.bits(u32::from(output_level), 8)
            })?;
            w.replace(id, |out| out.ue(sps_id))?;
            w.replace(width, |out| out.ue(self.width))?;
            w.replace(height, |out| out.ue(self.height))?;
            if self.uncropped {
                w.replace(crop..sps_reader.consumed, |out| out.bit(0))?;
            }
            Some(())
        })?;

        let mut pps_reader = Bits::new(pps, 34)?;
        let pps_range = unsigned_range(&mut pps_reader, 63)?;
        let sps_range = unsigned_range(&mut pps_reader, 15)?;
        let new_pps = splice(pps, &source_pps, |w| {
            w.replace(pps_range, |out| out.ue(pps_id))?;
            w.replace(sps_range, |out| out.ue(sps_id))?;
            w.replace(source.pps.tile_start..source.pps.tile_end, |out| {
                self.tiles(out)
            })
        })?;

        let nals = [new_vps, new_sps, new_pps];
        let parameters = Parameters::parse(nals.each_ref().map(Vec::as_slice))?;
        if !parameters.pps.tiles
            || parameters.pps.loop_across_tiles != self.loop_across_tiles
            || parameters.tile_columns != self.columns
            || parameters.tile_rows != self.rows
            || parameters.prefix.coded_width != self.width
            || parameters.prefix.coded_height != self.height
            || (self.uncropped
                && (parameters.prefix.display_width != self.width
                    || parameters.prefix.display_height != self.height))
        {
            return Err(Error::Metadata);
        }
        Ok(Output { nals, parameters })
    }

    fn tiles(&self, out: &mut Writer<'_>) -> Option<()> {
        out.bits(2, 2)?; // tiles_enabled_flag=1 and entropy_coding_sync_enabled_flag=0.
        out.ue((self.columns.len() - 1) as u32)?;
        out.ue((self.rows.len() - 1) as u32)?;
        let uniform = [self.columns, self.rows].iter().all(|parts| {
            let total: u32 = parts.iter().sum();
            let count = parts.len() as u32;
            parts.iter().enumerate().all(|(index, size)| {
                let index = index as u32;
                *size == (index + 1) * total / count - index * total / count
            })
        });
        out.bit(u32::from(uniform))?;
        if !uniform {
            for parts in [self.columns, self.rows] {
                for &size in &parts[..parts.len() - 1] {
                    out.ue(size - 1)?;
                }
            }
        }
        out.bit(u32::from(self.loop_across_tiles))
    }
}

impl Output {
    /// Compare the fully validated normalized bitstreams, ignoring only
    /// presentation VUI and level fields which the reference permits to differ.
    /// The actual emitted template is never modified by this comparison.
    pub(crate) fn coding_key(&self) -> Result<[Vec<u8>; 3], Error> {
        let [vps, sps, pps] = self.nals.each_ref().map(Vec::as_slice);
        let vps_rbsp = Bits::read_rbsp(vps, 32)?;
        let sps_rbsp = Bits::read_rbsp(sps, 33)?;
        let vui = self.parameters.sps.vui_start..self.parameters.sps.vui_end;
        let vps_levels = sublayer_level_ranges(vps, 32, 32, self.parameters.prefix.vps_sub_layers_minus_one)?;
        let sps_levels = sublayer_level_ranges(sps, 33, 8, self.parameters.prefix.max_sub_layers - 1)?;
        let vps_key = splice(vps, &vps_rbsp, |w| {
            w.replace(VPS_LEVEL..VPS_LEVEL + 8, |out| out.bits(0, 8))?;
            for range in vps_levels { w.replace(range, |out| out.bits(0, 8))?; }
            Some(())
        })?;
        let sps_key = splice(sps, &sps_rbsp, |w| {
            w.replace(SPS_LEVEL..SPS_LEVEL + 8, |out| out.bits(0, 8))?;
            for range in sps_levels { w.replace(range, |out| out.bits(0, 8))?; }
            w.replace(vui, |out| out.bit(0))
        })?;
        Ok([vps_key, sps_key, pps.to_vec()])
    }

    pub fn hvcc(&self) -> Result<Vec<u8>, Error> {
        let sps = Bits::read_rbsp(&self.nals[1], 33)?;
        let vps = Bits::read_rbsp(&self.nals[0], 32)?;
        let ptl = sps.get(1..13).ok_or(Error::Extent)?;
        if vps.get(4..16) != Some(ptl) {
            return Err(Error::Metadata);
        }
        let prefix = &self.parameters.prefix;
        if prefix.bit_depth_luma > 15 || prefix.bit_depth_chroma > 15 {
            return Err(Error::Unsupported);
        }
        let mut record = Vec::new();
        record
            .try_reserve_exact(38 + self.nals.iter().map(Vec::len).sum::<usize>())
            .map_err(|_| Error::Limit)?;
        record.push(1);
        record.extend_from_slice(ptl);
        record.extend_from_slice(&[
            0xf0,
            0,
            0xfe,
            0xfc | prefix.chroma_format as u8,
            0xf8 | (prefix.bit_depth_luma as u8 - 8),
            0xf8 | (prefix.bit_depth_chroma as u8 - 8),
            0,
            0,
            (prefix.max_sub_layers as u8) << 3 | ((sps[0] & 1) << 2) | 3,
            3,
        ]);
        for (index, nal) in self.nals.iter().enumerate() {
            let length = u16::try_from(nal.len()).map_err(|_| Error::Limit)?;
            record.extend_from_slice(&[0xa0 | index as u8, 0, 1]);
            record.extend_from_slice(&length.to_be_bytes());
            record.extend_from_slice(nal);
        }
        Ok(record)
    }
}

fn sublayer_level_ranges(nal: &[u8], kind: u8, prefix: u32, layers: u32)
    -> Result<Vec<Range<usize>>, Error>
{
    let mut bits = Bits::new(nal, kind)?;
    bits.read(prefix)?;
    for _ in 0..3 { bits.read(32)?; }
    let flags = bits.read(2 * layers)?;
    if layers != 0 { bits.read(2 * (8 - layers))?; }
    let mut ranges = Vec::new();
    for i in (0..layers).rev() {
        if flags & (2 << (2 * i)) != 0 {
            bits.read(32)?; bits.read(32)?; bits.read(24)?;
        }
        if flags & (1 << (2 * i)) != 0 {
            let start = bits.consumed;
            bits.read(8)?;
            ranges.push(start..bits.consumed);
        }
    }
    Ok(ranges)
}

fn valid_partition(values: &[u32], total: u32) -> bool {
    !values.is_empty()
        && values.len() <= 1024
        && !values.contains(&0)
        && values.iter().map(|&v| u64::from(v)).sum::<u64>() == u64::from(total)
}

fn unsigned_range(bits: &mut Bits<'_>, max: u32) -> Result<Range<usize>, Error> {
    let start = bits.consumed;
    bits.ue(max)?;
    Ok(start..bits.consumed)
}

struct Splice<'a, 'b> {
    source: &'a [u8],
    cursor: usize,
    source_end: usize,
    writer: Writer<'b>,
}

impl Splice<'_, '_> {
    fn replace(
        &mut self,
        range: Range<usize>,
        replacement: impl FnOnce(&mut Writer<'_>) -> Option<()>,
    ) -> Option<()> {
        if range.start < self.cursor || range.end < range.start || range.end > self.source_end {
            return None;
        }
        self.writer.copy(self.source, self.cursor, range.start)?;
        self.cursor = range.end;
        replacement(&mut self.writer)
    }
}

fn splice(
    nal: &[u8],
    source: &[u8],
    patch: impl FnOnce(&mut Splice<'_, '_>) -> Option<()>,
) -> Result<Vec<u8>, Error> {
    let last = source
        .iter()
        .rposition(|&byte| byte != 0)
        .ok_or(Error::Nal)?;
    let source_end = last * 8 + 7 - source[last].trailing_zeros() as usize;
    let mut rbsp = [0; MAX_PARAMETER_RBSP];
    let writer = Writer {
        data: &mut rbsp,
        pos: 0,
    };
    let mut operation = Splice {
        source,
        cursor: 0,
        source_end,
        writer,
    };
    patch(&mut operation).ok_or(Error::Limit)?;
    let mut writer = operation.writer;
    writer
        .copy(source, operation.cursor, source_end)
        .ok_or(Error::Limit)?;
    writer.bit(1).ok_or(Error::Limit)?;
    let bytes = writer.pos.div_ceil(8);
    let mut output = Vec::new();
    output
        .try_reserve_exact(bytes * 2 + 2)
        .map_err(|_| Error::Limit)?;
    output.extend_from_slice(&nal[..2]);
    let mut zeros = 0;
    for &byte in &rbsp[..bytes] {
        if zeros == 2 && byte <= 3 {
            output.push(3);
            zeros = 0;
        }
        output.push(byte);
        zeros = if byte == 0 { (zeros + 1).min(2) } else { 0 };
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(text: &str) -> Vec<u8> {
        text.as_bytes().chunks_exact(2).map(|pair|
            u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap()
        ).collect()
    }

    #[test]
    fn presentation_vui_does_not_change_coding_key_but_coding_syntax_does() {
        // Independently encoded 128x128 gray frames (libx265, no WPP/TMVP),
        // differing only in colour primaries BT.709 vs BT.2020.
        let vps = hex("40010c01ffff01600000030090000003000003001e959809");
        let pps = hex("4401c172b42240");
        let sps_a = hex("42010101600000030090000003000003001ea0102020596566924ca7016a02020208000003000800000300c840");
        let sps_b = hex("42010101600000030090000003000003001ea0102020596566924ca7016a12020208000003000800000300c840");
        let source = Parameters::parse([&vps, &sps_a, &pps]).unwrap();
        let columns = [source.prefix.width_in_ctbs];
        let rows = [source.prefix.height_in_ctbs];
        let mut request = Request {
            ids: [0;3], width: 128, height: 128, columns: &columns, rows: &rows,
            frame_rate: (25,1), loop_across_tiles: false, uncropped: false,
        };
        let first = request.generate([&vps, &sps_a, &pps]).unwrap();
        let second = request.generate([&vps, &sps_b, &pps]).unwrap();
        assert_ne!(first.nals[1], second.nals[1]);
        assert_eq!(first.coding_key().unwrap(), second.coding_key().unwrap());
        request.loop_across_tiles = true;
        let changed = request.generate([&vps, &sps_a, &pps]).unwrap();
        assert_ne!(first.coding_key().unwrap(), changed.coding_key().unwrap());
    }
}
