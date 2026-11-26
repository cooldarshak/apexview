//! Metadata-only CMAF audio timing. This receives only complete tfhd/tfdt/
//! trun box payloads selected by the existing authorized inspector. No
//! encrypted media, keys, IVs, PSSH, license data or opaque tails enter it.
use crate::bmff::{Error, Reader};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Defaults {
    pub duration: Option<u32>,
    pub size: Option<u32>,
    pub flags: Option<u32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Header {
    pub track: u32,
    pub defaults: Defaults,
    pub description: Option<u32>,
    pub empty: bool,
}

pub fn header(bytes: &[u8], mut defaults: Defaults) -> Result<Header, Error> {
    let mut r=Reader::new(bytes);
    let (version,flags)=r.full()?;
    if version!=0 || flags & !0x03003b!=0 || flags&1!=0 && flags&0x020000!=0 {
        return Err(Error::Unsupported);
    }
    let track=r.u32()?;
    if track==0{return Err(Error::Metadata);}
    if flags&1!=0{r.u64()?;}
    let description=if flags&2!=0{let v=r.u32()?;if v==0{return Err(Error::Metadata);}Some(v)}else{None};
    if flags&8!=0 {defaults.duration=Some(r.u32()?).filter(|v|*v>0).or(defaults.duration);}
    if flags&16!=0 {defaults.size=Some(r.u32()?).filter(|v|*v>0).or(defaults.size);}
    if flags&32!=0 {defaults.flags=Some(r.u32()?);}
    r.finish()?;
    Ok(Header{track,defaults,description,empty:flags&0x010000!=0})
}

pub fn decode_time(bytes:&[u8])->Result<i64,Error>{
    let mut r=Reader::new(bytes);
    let t=match r.full()?{
        (0,0)=>i64::from(r.u32()?),
        (1,0)=>i64::try_from(r.u64()?).map_err(|_|Error::Timing)?,
        _=>return Err(Error::Unsupported),
    };
    r.finish()?;Ok(t)
}

/// Return count and exact end decode time. No sample vector allocations.
/// Caller retains the existing independent encryption/extent validation.
pub fn run(bytes:&[u8],defaults:Defaults,start:i64)->Result<(u32,i64),Error>{
    let mut r=Reader::new(bytes);
    let (version,flags)=r.full()?;
    if version>1||flags&!0xf05!=0||flags&4!=0&&flags&0x400!=0{return Err(Error::Unsupported);}
    let count=r.u32()?;
    if count>100_000{return Err(Error::Limit);}
    if flags&1!=0{r.i32()?;}
    if flags&4!=0{r.u32()?;}
    let mut cursor=start;
    for _ in 0..count{
        let duration=if flags&0x100!=0{Some(r.u32()?)}else{defaults.duration};
        if flags&0x200!=0{r.u32()?;}
        if flags&0x400!=0{r.u32()?;}
        let cto=if flags&0x800==0{0}else if version==0{i64::from(r.u32()?)}else{i64::from(r.i32()?)};
        cursor.checked_add(cto).ok_or(Error::Timing)?;
        let duration=duration.filter(|&v|v>0).ok_or(Error::Timing)?;
        cursor=cursor.checked_add(i64::from(duration)).ok_or(Error::Timing)?;
    }
    r.finish()?;
    Ok((count,cursor))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn words(values:&[u32])->Vec<u8>{values.iter().flat_map(|v|v.to_be_bytes()).collect()}
    #[test]
    fn metadata_only_defaults_and_signed_offsets(){
        let h=header(&words(&[0x020008,7,1024]),Defaults::default()).unwrap();
        assert_eq!(h.track,7);
        assert_eq!(decode_time(&words(&[0x01000000,0,48_000])),Ok(48_000));
        let bytes=words(&[0x01000a01,2,100,256,u32::MAX,192,0]);
        assert_eq!(run(&bytes,h.defaults,48000),Ok((2,50048)));
        assert!(run(&bytes[..bytes.len()-1],h.defaults,48000).is_err());
        assert!(run(&bytes,h.defaults,i64::MAX).is_err());
        assert!(header(&words(&[0x020001,7]),Defaults::default()).is_err());
    }
}
