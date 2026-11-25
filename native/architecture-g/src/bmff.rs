//! Bounded, borrowed ISO BMFF extents used by the clear CMAF port.
pub(crate) const MAX_BYTES: usize = 8 * 1024 * 1024;
const MAX_BOXES: usize = 20_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    Extent,
    Unsupported,
    Protected,
    Metadata,
    Limit,
    Timing,
    Nal,
}

pub(crate) struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Self {
        Self(bytes)
    }

    pub fn take(&mut self, size: usize) -> Result<&'a [u8], Error> {
        let (field, rest) = self.0.split_at_checked(size).ok_or(Error::Extent)?;
        self.0 = rest;
        Ok(field)
    }

    pub fn u8(&mut self) -> Result<u8, Error> {
        Ok(self.take(1)?[0])
    }

    pub fn u16(&mut self) -> Result<u16, Error> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }

    pub fn u32(&mut self) -> Result<u32, Error> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }

    pub fn i32(&mut self) -> Result<i32, Error> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }

    pub fn u64(&mut self) -> Result<u64, Error> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }

    pub fn full(&mut self) -> Result<(u8, u32), Error> {
        let header = self.u32()?;
        Ok(((header >> 24) as u8, header & 0x00ff_ffff))
    }

    pub fn rest(&self) -> &'a [u8] {
        self.0
    }

    pub fn finish(&self) -> Result<(), Error> {
        if self.0.is_empty() {
            Ok(())
        } else {
            Err(Error::Extent)
        }
    }
}

#[derive(Clone, Copy)]
pub(crate) struct BoxView<'a> {
    pub kind: [u8; 4],
    pub offset: usize,
    pub payload_offset: usize,
    pub payload: &'a [u8],
}

impl<'a> BoxView<'a> {
    pub fn children(self) -> Boxes<'a> {
        Boxes::new(self.payload, self.payload_offset)
    }
}

#[derive(Clone)]
pub(crate) struct Boxes<'a> {
    bytes: &'a [u8],
    offset: usize,
    count: usize,
}

impl<'a> Boxes<'a> {
    pub fn root(bytes: &'a [u8]) -> Result<Self, Error> {
        if bytes.len() > MAX_BYTES {
            return Err(Error::Limit);
        }
        Ok(Self::new(bytes, 0))
    }

    pub fn new(bytes: &'a [u8], offset: usize) -> Self {
        Self {
            bytes,
            offset,
            count: 0,
        }
    }

    pub fn optional(self, kind: &[u8; 4]) -> Result<Option<BoxView<'a>>, Error> {
        let mut found = None;
        for child in self {
            let child = child?;
            if &child.kind == kind && found.replace(child).is_some() {
                return Err(Error::Metadata);
            }
        }
        Ok(found)
    }

    pub fn require(self, kind: &[u8; 4]) -> Result<BoxView<'a>, Error> {
        self.optional(kind)?.ok_or(Error::Metadata)
    }

    fn next_box(&mut self) -> Result<BoxView<'a>, Error> {
        if self.count >= MAX_BOXES {
            return Err(Error::Limit);
        }
        let mut reader = Reader::new(self.bytes);
        let size = reader.u32()?;
        let kind = reader.take(4)?.try_into().unwrap();
        let (size, header) = match size {
            0 => return Err(Error::Unsupported),
            1 => (
                usize::try_from(reader.u64()?).map_err(|_| Error::Extent)?,
                16,
            ),
            size => (size as usize, 8),
        };
        let payload = reader.take(size.checked_sub(header).ok_or(Error::Extent)?)?;
        // At every level the offset and extent derive from the bounded root slice.
        let view = BoxView {
            kind,
            offset: self.offset,
            payload_offset: self.offset + header,
            payload,
        };
        self.bytes = reader.rest();
        self.offset += size;
        self.count += 1;
        if matches!(
            &view.kind,
            b"sinf"
                | b"schm"
                | b"schi"
                | b"tenc"
                | b"pssh"
                | b"senc"
                | b"saiz"
                | b"saio"
                | b"sbgp"
                | b"sgpd"
                | b"uuid"
                | b"encv"
                | b"enca"
        ) {
            return Err(Error::Protected);
        }
        Ok(view)
    }
}

impl<'a> Iterator for Boxes<'a> {
    type Item = Result<BoxView<'a>, Error>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.bytes.is_empty() {
            return None;
        }
        let entry = self.next_box();
        if entry.is_err() {
            self.bytes = &[];
        }
        Some(entry)
    }
}
