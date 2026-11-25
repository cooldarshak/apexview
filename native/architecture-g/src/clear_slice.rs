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

// Safe word-at-a-time optimization for the already decoded prefix's remaining
// clear EBSP tail. Skip only words containing no zero bytes when the carried
// zero count is zero. All other bytes retain the original scalar EPB rules,
// including malformed sequences and a prevention byte at the very end.
fn no_zero_word(bytes: &[u8]) -> bool {
    let word = u64::from_ne_bytes(bytes.try_into().expect("eight-byte word"));
    let ones = 0x0101_0101_0101_0101u64;
    let high = 0x8080_8080_8080_8080u64;
    ((word.wrapping_sub(ones)) & !word & high) == 0
}

fn decode_prefix<const FAST: bool>(source: &[u8], rbsp: &mut [u8], required: usize) -> Option<usize> {
    let mut i = 2usize;
    let mut count = 0usize;
    let mut tail = 0usize;
    let mut zeros = 0usize;
    while i < source.len() {
        if FAST && count >= required && zeros == 0 && source.len()-i >= 8 &&
            no_zero_word(&source[i..i+8]) {
            i += 8;
            count += 8;
            continue;
        }
        let v = source[i];
        if zeros >= 2 && v == 3 {
            if *source.get(i+1)? > 3 {return None;}
            zeros = 0;
            i += 1;
            continue;
        }
        if count < required {
            rbsp[count] = v;
            if count == required-1 {tail = i;}
        }
        count += 1;
        zeros = if v == 0 {zeros+1} else {0};
        i += 1;
    }
    (count >= required && tail >= 2).then_some(tail)
}

/// Rewrites prevalidated clear syntax, retains exact borrowed CABAC tail.
/// No write to output until all source and destination bounds are validated.
pub fn rewrite(source:&[u8], s:Syntax, output:&mut [u8])->Option<usize> {
    rewrite_impl::<true>(source,s,output)
}

/// Host diagnostic control: same bounds, syntax, output and full validation,
/// only the tail scan remains scalar. Not a separate production engine.
pub fn rewrite_scalar(source:&[u8], s:Syntax, output:&mut [u8])->Option<usize> {
    rewrite_impl::<false>(source,s,output)
}

fn rewrite_impl<const FAST: bool>(source: &[u8], s: Syntax, output: &mut [u8]) -> Option<usize> {
    with_rewritten_parts::<FAST, _>(source, s, |prefix, tail| {
        let total = prefix.len() + tail.len();
        let out = output.get_mut(..total)?;
        let (header, payload) = out.split_at_mut(prefix.len());
        header.copy_from_slice(prefix);
        payload.copy_from_slice(tail);
        Some(total)
    })?
}

pub struct Parts<'a> {
    pub(crate) header: Vec<u8>,
    pub(crate) tail: &'a [u8],
}

/// Owns the escaped header only. The VCL payload remains in the caller's clear
/// input until final fragment assembly. Syntax still comes from the JVM reference.
pub fn rewrite_parts(source: &[u8], syntax: Syntax) -> Option<Parts<'_>> {
    with_rewritten_parts::<true, _>(source, syntax, |prefix, tail| {
        let mut header = Vec::new();
        header.try_reserve_exact(prefix.len()).ok()?;
        header.extend_from_slice(prefix);
        Some(Parts { header, tail })
    })?
}

fn with_rewritten_parts<'a, const FAST: bool, T>(
    source: &'a [u8],
    s: Syntax,
    consume: impl FnOnce(&[u8], &'a [u8]) -> T,
) -> Option<T> {
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
    let tail=decode_prefix::<FAST>(source,&mut rbsp,required)?;
    let mut zeros;
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
    while !w.pos.is_multiple_of(8) {w.bit(0)?;}
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
    if total > MAX { return None; }
    Some(consume(&prefix[..pos], &source[tail..]))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn word_scan_matches_scalar_at_every_tail_boundary() {
        // Dense zero/03 patterns, malformed escapes and nonzero skip words.
        // Compare accept/reject, exact decoded prefix and the original splice offset.
        let mut seed=0x7142_8371u32;
        for len in 5..160 {
            for phase in 0..16 {
                let mut source=vec![2u8;len];
                source[1]=1;
                for v in &mut source[2..] {
                    seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);
                    *v=(seed>>24) as u8;
                }
                let i=2+phase;
                if i+3<len {
                    source[i..i+4].copy_from_slice(&[0,0,3,(phase%6) as u8]);
                }
                for required in 1..=8.min(len-2) {
                    let mut fast=[0u8;8];
                    let mut scalar=[0u8;8];
                    let a=decode_prefix::<true>(&source,&mut fast,required);
                    let b=decode_prefix::<false>(&source,&mut scalar,required);
                    assert_eq!(a,b,"len={len} phase={phase} required={required}");
                    assert_eq!(fast,scalar);
                }
            }
        }
    }

    #[test]
    fn late_malformed_escape_rejects_without_output_mutation() {
        let syntax=Syntax{address_end:2,entry_start:2,entry_end:2,alignment:2,
            header_bytes:1,destination:0,address_bits:1,pps:0,dependent_flag_present:false,tiles:false,no_output:false};
        for offset in 8..32 {
            let mut source=vec![0x55;offset+4];
            source[0]=2;
            source[1]=1;
            source[offset..].copy_from_slice(&[0,0,3,4]);
            let mut fast=[0x77;64];
            let mut scalar=fast;
            assert_eq!(rewrite(&source,syntax,&mut fast),None);
            assert_eq!(rewrite_scalar(&source,syntax,&mut scalar),None);
            assert_eq!(fast,[0x77;64]);
            assert_eq!(scalar,fast);
            source.pop(); // terminal emulation-prevention byte
            assert_eq!(rewrite(&source,syntax,&mut fast),None);
            assert_eq!(fast,[0x77;64]);
        }
    }

    #[test]
    fn invalid_descriptor_cannot_publish() {
        let mut out=[0x55;32];
        let s=Syntax{address_end:3,entry_start:2,entry_end:3,alignment:4,
            header_bytes:1,destination:0,address_bits:1,pps:0,dependent_flag_present:false,tiles:false,no_output:false};
        assert_eq!(rewrite(&[2,1,0xff,1,2,3],s,&mut out),None);
        assert_eq!(out,[0x55;32]);
    }
}
