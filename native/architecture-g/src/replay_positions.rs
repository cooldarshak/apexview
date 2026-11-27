//! Canonical F1 position record fast path. Strictly bounded and transactional:
//! unsupported JSON forms return None and the caller uses its exact reference
//! parser. This is not a general-purpose JSON or timestamp decoder.
pub const MAX_ROWS: usize = 512;
pub const HEADER: usize = 3;
pub const WORDS: usize = HEADER + MAX_ROWS * 4;
const MAX_RECORD: usize = 512 * 1024;

struct Parser<'a> { bytes: &'a [u8], at: usize }
impl<'a> Parser<'a> {
    fn ws(&mut self) {
        while self.bytes.get(self.at).is_some_and(|v| matches!(*v,b' '|b'\n'|b'\r'|b'\t')) { self.at += 1; }
    }
    fn take(&mut self, value:u8) -> bool {
        self.ws();
        if self.bytes.get(self.at)==Some(&value) { self.at+=1; true } else { false }
    }
    fn expect(&mut self, value:u8)->Option<()> { self.take(value).then_some(()) }
    fn string(&mut self)->Option<&'a [u8]> {
        self.expect(b'"')?;
        let start=self.at;
        loop {
            match *self.bytes.get(self.at)? {
                b'"'=>{ let s=&self.bytes[start..self.at];self.at+=1;return Some(s); },
                b'\\'|0..=31|127..=255=>return None,
                _=>self.at+=1,
            }
        }
    }
    fn integer(&mut self)->Option<i32>{
        self.ws();
        let start=self.at;
        let negative=self.bytes.get(self.at)==Some(&b'-');
        if negative {self.at+=1;}
        let first=*self.bytes.get(self.at)?;
        if !first.is_ascii_digit(){return None;}
        self.at+=1;
        if first!=b'0'{
            while self.bytes.get(self.at).is_some_and(u8::is_ascii_digit){self.at+=1;}
        }
        // Negative zero could preserve a distinct floating-point bit pattern
        // in reference JSON; decline instead of normalizing it.
        if negative && first==b'0' {return None;}
        std::str::from_utf8(&self.bytes[start..self.at]).ok()?.parse().ok()
    }
    fn object(&mut self, mut field:impl FnMut(&mut Self,&'a [u8])->Option<()>)->Option<()>{
        self.expect(b'{')?;
        if self.take(b'}'){return Some(());}
        loop {
            let key=self.string()?; self.expect(b':')?;field(self,key)?;
            if self.take(b'}'){return Some(());}
            self.expect(b',')?;
        }
    }
    fn array(&mut self,mut element:impl FnMut(&mut Self)->Option<()>)->Option<()>{
        self.expect(b'[')?;
        if self.take(b']'){return Some(());}
        loop {
            element(self)?;
            if self.take(b']'){return Some(());}
            self.expect(b',')?;
        }
    }
}
fn unique(mask:&mut u8,bit:u8)->Option<()> {
    if *mask&bit!=0{return None;}
    *mask|=bit;Some(())
}
fn digits(bytes:&[u8])->Option<i64>{
    if bytes.is_empty()||!bytes.iter().all(u8::is_ascii_digit){return None;}
    bytes.iter().try_fold(0i64,|n,b|n.checked_mul(10)?.checked_add(i64::from(*b-b'0')))
}
fn utc_ms(bytes:&[u8])->Option<i64>{
    if bytes.len()<20||bytes.get(4)!=Some(&b'-')||bytes.get(7)!=Some(&b'-')||
        bytes.get(10)!=Some(&b'T')||bytes.get(13)!=Some(&b':')||
        bytes.get(16)!=Some(&b':')||bytes.last()!=Some(&b'Z'){return None;}
    let y=digits(bytes.get(0..4)?)?;
    let m=digits(bytes.get(5..7)?)?;
    let d=digits(bytes.get(8..10)?)?;
    let h=digits(bytes.get(11..13)?)?;
    let min=digits(bytes.get(14..16)?)?;
    let sec=digits(bytes.get(17..19)?)?;
    if !(1970..=9999).contains(&y)||!(1..=12).contains(&m)||h>23||min>59||sec>59{return None;}
    let leap=y%4==0&&(y%100!=0||y%400==0);
    let months=[31,if leap{29}else{28},31,30,31,30,31,31,30,31,30,31];
    if !(1..=months[m as usize-1]).contains(&d){return None;}
    let mut days=365*(y-1970)+(y-1)/4-1969/4-((y-1)/100-1969/100)+(y-1)/400-1969/400;
    days+=months[..m as usize-1].iter().sum::<i64>()+d-1;
    let millis=if bytes.len()==20{0}else{
        if bytes.get(19)!=Some(&b'.')||!(22..=30).contains(&bytes.len()){return None;}
        let f=&bytes[20..bytes.len()-1];
        digits(f)?;
        let mut ms=0;
        for i in 0..3{ms=ms*10+i64::from(f.get(i).copied().unwrap_or(b'0')-b'0');}
        ms
    };
    Some((((days*24+h)*60+min)*60+sec)*1000+millis)
}
fn driver(key:&[u8])->Option<usize>{
    if key.len()>3||(key.len()>1&&key.first()==Some(&b'0')){return None;}
    usize::try_from(digits(key)?).ok()
}

/// Layout: sample count, row count, latest UTC (MIN if empty), then each
/// row's UTC, numeric racing number and exact IEEE754 X/Y bits. On rejection
/// caller must discard scratch. No partial rows are ever committed via JNI.
pub fn parse(bytes:&[u8],out:&mut [i64])->Option<usize>{
    if bytes.len()>MAX_RECORD||out.len()<WORDS||!bytes.is_ascii(){return None;}
    let mut p=Parser{bytes,at:0};
    let mut root=0;
    let mut samples=0usize;
    let mut rows=0usize;
    let mut latest=i64::MIN;
    p.object(|p,key|{
        if key!=b"Position"{return None;}
        unique(&mut root,1)?;
        p.array(|p|{
            samples+=1;
            if samples>128{return None;}
            let begin=rows;
            let mut fields=0;
            let mut time=None;
            p.object(|p,key|{
                match key{
                    b"Timestamp"=>{unique(&mut fields,1)?;time=Some(utc_ms(p.string()?)?);},
                    b"Entries"=>{
                        unique(&mut fields,2)?;
                        let mut seen=[false;1000];
                        p.object(|p,key|{
                            let n=driver(key)?;
                            if *seen.get(n)?{return None;}seen[n]=true;
                            let mut bits=0;
                            let(mut x,mut y)=(None,None);
                            let mut off=false;
                            p.object(|p,key|{
                                match key{
                                    b"Status"=>{unique(&mut bits,1)?;off=p.string()?==b"OffTrack";},
                                    b"X"=>{unique(&mut bits,2)?;x=Some(p.integer()?);},
                                    b"Y"=>{unique(&mut bits,4)?;y=Some(p.integer()?);},
                                    b"Z"=>{unique(&mut bits,8)?;p.integer()?;},
                                    _=>return None,
                                }
                                Some(())
                            })?;
                            if !off {
                                if rows>=MAX_ROWS{return None;}
                                let i=HEADER+rows*4;
                                out[i+1]=n as i64;
                                out[i+2]=i64::from((x? as f32).to_bits());
                                out[i+3]=i64::from((y? as f32).to_bits());
                                rows+=1;
                            }
                            Some(())
                        })?;
                    },
                    _=>return None,
                }
                Some(())
            })?;
            let time=time?;
            latest=latest.max(time);
            for i in begin..rows{out[HEADER+i*4]=time;}
            Some(())
        })
    })?;
    p.ws();
    if root!=1||p.at!=bytes.len(){return None;}
    out[..HEADER].copy_from_slice(&[samples as i64,rows as i64,latest]);
    Some(HEADER+rows*4)
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn exact_utc_and_fraction(){
        assert_eq!(utc_ms(b"1970-01-01T00:00:00Z"),Some(0));
        assert_eq!(utc_ms(b"2000-02-29T00:00:00.1234567Z"),Some(951782400123));
        assert_eq!(utc_ms(b"2026-10-24T20:00:00.1Z"),Some(1792872000100));
        assert_eq!(utc_ms(b"2100-02-29T00:00:00Z"),None);
        assert_eq!(utc_ms(b"2026-01-01T00:00:60Z"),None);
    }
    #[test]
    fn canonical_positions_skip_offtrack_and_preserve_key_order(){
        let mut out=[0;WORDS];
        let record=br#"{"Position":[{"Entries":{"44":{"Y":-12,"X":100,"Status":"OnTrack","Z":0},"1":{"Status":"OffTrack"}},"Timestamp":"1970-01-01T00:00:01.0010000Z"},{"Timestamp":"1970-01-01T00:00:02Z","Entries":{"4":{"X":0,"Y":32}}}]}"#;
        assert_eq!(parse(record,&mut out),Some(11));
        assert_eq!(&out[..11],&[2,2,2000,1001,44,100f32.to_bits() as i64,(-12f32).to_bits() as i64,2000,4,0,32f32.to_bits() as i64]);
    }
    #[test]
    fn unsupported_records_decline_without_committing(){
        let mut out=[0;WORDS];
        for bad in [
            br#"{"Position":[],"Position":[]}"#.as_slice(),
            br#"{"Position":[{"Timestamp":"1970-01-01T00:00:01Z","Entries":{"01":{"X":1,"Y":2}}}]}"#,
            br#"{"Position":[{"Timestamp":"1970-01-01T00:00:01Z","Entries":{"1":{"X":1.2,"Y":2}}}]}"#,
            br#"{"Position":[{"Timestamp":"1970-01-01T00:00:01Z","Entries":{"1":{"X":1,"Y":2,"X":3}}}]}"#,
            br#"{"Position":[{"Timestamp":"1970-01-01T00:00:01Z","Entries":{"1":{"X":1,"Y":2}}},]}"#,
        ]{assert_eq!(parse(bad,&mut out),None);}
    }
}
