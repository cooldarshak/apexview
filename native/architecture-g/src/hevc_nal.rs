//! Borrowed length-prefixed NAL units and the syntax common to all HEVC headers.
use crate::bmff::{Error, Reader, MAX_BYTES};

const MAX_NALS: usize = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Header {
    pub unit_type: u8,
    pub layer_id: u8,
    pub temporal_id_plus_one: u8,
}

pub fn header(nal: &[u8]) -> Result<Header, Error> {
    let [first, second, ..] = *nal else {
        return Err(Error::Nal);
    };
    let temporal_id_plus_one = second & 7;
    if first & 0x80 != 0 || temporal_id_plus_one == 0 {
        return Err(Error::Nal);
    }
    Ok(Header {
        unit_type: (first >> 1) & 0x3f,
        layer_id: ((first & 1) << 5) | (second >> 3),
        temporal_id_plus_one,
    })
}

pub struct NalUnits<'a> {
    reader: Reader<'a>,
    prefix: usize,
    count: usize,
    failed: bool,
}

impl<'a> NalUnits<'a> {
    pub fn new(sample: &'a [u8], prefix: usize) -> Result<Self, Error> {
        if !(1..=4).contains(&prefix) || sample.is_empty() {
            return Err(Error::Nal);
        }
        if sample.len() > MAX_BYTES {
            return Err(Error::Limit);
        }
        Ok(Self {
            reader: Reader::new(sample),
            prefix,
            count: 0,
            failed: false,
        })
    }

    fn read_nal(&mut self) -> Result<(&'a [u8], Header), Error> {
        if self.count == MAX_NALS {
            return Err(Error::Limit);
        }
        let size = self
            .reader
            .take(self.prefix)?
            .iter()
            .fold(0usize, |size, byte| (size << 8) | usize::from(*byte));
        let nal = self.reader.take(size)?;
        let info = header(nal)?;
        self.count += 1;
        Ok((nal, info))
    }
}

impl<'a> Iterator for NalUnits<'a> {
    type Item = Result<(&'a [u8], Header), Error>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.failed || self.reader.rest().is_empty() {
            return None;
        }
        let nal = self.read_nal();
        self.failed = nal.is_err();
        Some(nal)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_prefix_widths_borrow_exact_nals_and_reject_malformed_streams() {
        let nal = [0x26, 0x01, 0x80, 0x01, 0x02];
        let expected = Header {
            unit_type: 19,
            layer_id: 0,
            temporal_id_plus_one: 1,
        };
        for prefix in 1..=4 {
            let len = (nal.len() as u32).to_be_bytes();
            let sample = [&len[4 - prefix..], &nal[..], &len[4 - prefix..], &nal[..]].concat();
            let mut parsed = NalUnits::new(&sample, prefix).unwrap();
            assert_eq!(parsed.next(), Some(Ok((&nal[..], expected))));
            assert_eq!(
                parsed.next().unwrap().unwrap().0.as_ptr(),
                sample[prefix * 2 + nal.len()..].as_ptr()
            );
            assert_eq!(parsed.next(), None);
            let mut truncated = NalUnits::new(&sample[..sample.len() - 1], prefix).unwrap();
            assert!(truncated.next().unwrap().is_ok());
            assert_eq!(truncated.next(), Some(Err(Error::Extent)));
            assert_eq!(truncated.next(), None);
        }
        assert!(matches!(NalUnits::new(&nal, 0), Err(Error::Nal)));
        assert_eq!(header(&[0xa6, 1]), Err(Error::Nal));
        assert_eq!(header(&[0x26, 0]), Err(Error::Nal));
    }
}
