//! Feasibility-only clear slice-header transformation with JVM-validated syntax offsets.
//! No protected samples, no production parsing or player integration.
const HEADER: usize = 8192;
const MAX: usize = 8 * 1024 * 1024;

#[derive(Clone, Copy)]
pub struct Syntax {
    pub address_end: usize,
    pub entry_start: usize,
    pub entry_end: usize,
    pub alignment: usize,
    pub header_bytes: usize,
    pub destination: u32,
    pub address_bits: usize,
    pub pps: u32,
    pub dependent_flag_present: bool,
    pub tiles: bool,
    pub no_output: bool,
}

struct Writer<'a> { data: &'a mut [u8], pos: usize }
impl Writer<'_> {
    fn bit(&mut self, v: u32) -> Option<()> {
        let b = self.data.get_mut(self.pos / 8)?;
        if v != 0 { *b |= 1 << (7 - self.pos % 8); }
        self.pos += 1;
        Some(())
    }
    fn bits(&mut self,v:u32,n:usize)->Option<()> {
        if n>32 || (n<32 && v>>n != 0) {return None;}
        for i in (0..n).rev() {self.bit((v>>i)&1)?;}
        Some(())
    }
    fn ue(&mut self,v:u32)->Option<()> {
        let code=v.checked_add(1)?;
        let width=(32-code.leading_zeros()) as usize;
        for _ in 1..width {self.bit(0)?;}
        self.bits(code,width)
    }
    fn copy(&mut self,src:&[u8],from:usize,to:usize)->Option<()> {
        if from>to || to>src.len()*8 {return None;}
        for i in from..to { self.bit(u32::from((src[i/8]>>(7-i%8))&1))?; }
        Some(())
    }
}

/// Rewrites prevalidated clear syntax, retains exact borrowed CABAC tail.
/// No write to output until all source and destination bounds are validated.
pub fn rewrite(source:&[u8], s:Syntax, output:&mut [u8])->Option<usize> {
    if !(5..=MAX).contains(&source.len()) || source[0]&0x81 != 0 ||
        source[1]>>3 != 0 || source[1]&7==0 || (source[0]>>1)>21 ||
        s.header_bytes==0 || s.header_bytes>HEADER-3 ||
        s.address_end>s.entry_start || s.entry_start>s.entry_end ||
        s.entry_end>s.alignment || s.alignment>=s.header_bytes*8 ||
        s.address_bits>32 || (s.address_bits<32 && s.destination>>s.address_bits!=0) {
        return None;
    }
    let required=s.header_bytes+3;
    let mut rbsp=[0u8;HEADER];
    let mut count=0usize;
    let mut tail=0usize;
    let mut zeros=0usize;
    for (i,&v) in source.iter().enumerate().skip(2) {
        if zeros>=2 && v==3 {
            if *source.get(i+1)?>3 {return None;}
            zeros=0;
            continue;
        }
        if count<required {
            rbsp[count]=v;
            if count==required-1 {tail=i;}
        }
        count+=1;
        zeros=if v==0 {zeros+1} else {0};
    }
    if count<required || tail<2 {return None;}
    let mut header=[0u8;HEADER];
    let mut w=Writer{data:&mut header,pos:0};
    w.bit(u32::from(s.destination==0))?;
    if (16..=21).contains(&(source[0]>>1)) {w.bit(u32::from(s.no_output))?;}
    w.ue(s.pps)?;
    if s.destination!=0 {
        if s.dependent_flag_present {w.bit(0)?;}
        w.bits(s.destination,s.address_bits)?;
    }
    w.copy(&rbsp,s.address_end,s.entry_start)?;
    if s.tiles {w.ue(0)?;}
    w.copy(&rbsp,s.entry_end,s.alignment)?;
    w.bit(1)?;
    while w.pos%8!=0 {w.bit(0)?;}
    let n=w.pos/8;
    if n+2>HEADER {return None;}
    header[n..n+2].copy_from_slice(&rbsp[s.header_bytes..s.header_bytes+2]);
    let mut prefix=[0u8;HEADER*2];
    prefix[..2].copy_from_slice(&source[..2]);
    let mut pos=2;
    zeros=0;
    for &v in &header[..n+2] {
        if zeros>=2 && v<=3 {prefix[pos]=3;pos+=1;zeros=0;}
        prefix[pos]=v;pos+=1;
        zeros=if v==0 {zeros+1} else {0};
    }
    if zeros>=2 && rbsp[s.header_bytes+2]<=3 {prefix[pos]=3;pos+=1;}
    let total=pos.checked_add(source.len()-tail)?;
    if total>MAX || total>output.len() {return None;}
    output[..pos].copy_from_slice(&prefix[..pos]);
    output[pos..total].copy_from_slice(&source[tail..]);
    Some(total)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_descriptor_cannot_publish() {
        let mut out=[0x55;32];
        let s=Syntax{address_end:3,entry_start:2,entry_end:3,alignment:4,
            header_bytes:1,destination:0,address_bits:1,pps:0,dependent_flag_present:false,tiles:false,no_output:false};
        assert_eq!(rewrite(&[2,1,0xff,1,2,3],s,&mut out),None);
        assert_eq!(out,[0x55;32]);
    }
}
