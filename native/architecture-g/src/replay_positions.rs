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


/** Bounded sparse index for the disk spool. No JSON or media payload is retained.
 * The single spool owner supplies byte offsets only after validating chronology. */
#[derive(Default)]
pub struct SpoolIndex { marks: Vec<(i64,i64)> }
impl SpoolIndex {
    pub fn mark(&mut self, time:i64, offset:i64)->Option<()> {
        if time<0 || !(0..64*1024*1024).contains(&offset) ||
            self.marks.len()>=65536 ||
            self.marks.last().is_some_and(|&(t,o)| time<t || offset<=o) { return None; }
        self.marks.try_reserve(1).ok()?;
        self.marks.push((time,offset));
        Some(())
    }
    pub fn start_offset(&self, from:i64)->i64 {
        let at=self.marks.partition_point(|&(time,_)| time<from);
        if at<=1 {0} else {self.marks[at-1].1}
    }
}
#[cfg(test)]
mod spool_tests {
    use super::*;
    #[test]
    fn seek_and_equal_time_boundaries() {
        let mut index=SpoolIndex::default();
        for (t,o) in [(0,0),(10,100),(20,200),(20,300),(30,400)] {
            assert_eq!(index.mark(t,o),Some(()));
        }
        assert_eq!(index.start_offset(20),100);
        assert_eq!(index.start_offset(21),300);
        assert_eq!(index.start_offset(5),0);
        assert_eq!(index.start_offset(100),400);
        assert_eq!(index.mark(29,500),None);
        assert_eq!(index.mark(31,400),None);
        assert_eq!(index.start_offset(100),400);
    }
    #[test]
    fn cap_is_enforced_without_mutating_index() {
        let mut index=SpoolIndex::default();
        for i in 0..65536 { assert_eq!(index.mark(i,i),Some(())); }
        assert_eq!(index.mark(65536,65536),None);
        assert_eq!(index.start_offset(100000),65535);
    }
}


pub const TOWER_ROWS: usize = 128;
pub const TOWER_FIELDS: usize = 6;
pub const TOWER_WORDS: usize = 1 + TOWER_ROWS * TOWER_FIELDS;
const ABSENT: i64 = -1;
const NO_POSITION: i64 = i64::MIN;

/// Canonical position/gap-only TimingData record. Full lifecycle, sector,
/// lap and supplemental semantics stay on the reference path when any
/// unsupported field occurs. No row is committed until the entire record
/// is validated. Strings are ASCII spans into the caller's same JSON text.
fn text_span(p: &mut Parser<'_>) -> Option<i64> {
    let value = p.string()?;
    let end = p.at.checked_sub(1)?;
    let start = end.checked_sub(value.len())?;
    Some(((i64::try_from(start).ok()?) << 32) | i64::try_from(end).ok()?)
}
fn value_span(p: &mut Parser<'_>) -> Option<i64> {
    let mut mask = 0u8;
    let mut span = ABSENT;
    p.object(|p,key| {
        if key != b"Value" { return None; }
        unique(&mut mask, 1)?;
        span = text_span(p)?;
        Some(())
    })?;
    Some(span)
}
fn position_value(p: &mut Parser<'_>) -> Option<i64> {
    p.ws();
    let position = if p.bytes.get(p.at) == Some(&b'"') {
        std::str::from_utf8(p.string()?).ok()?.parse::<i32>().ok()?
    } else { p.integer()? };
    Some(i64::from(position))
}
// Validate a canonical nested value without allocating a JSON tree. Escaped
// strings, non-ASCII and excessive nesting use the exact reference path.
// Complex driver rows are dispatched individually after the whole envelope
// passes validation; simple rows never allocate a JSONObject.
fn skip_value(p: &mut Parser<'_>, depth: usize) -> Option<()> {
    if depth >= 32 { return None; }
    p.ws();
    match *p.bytes.get(p.at)? {
        b'{' => p.object(|p, _| skip_value(p, depth + 1)),
        b'[' => p.array(|p| skip_value(p, depth + 1)),
        b'"' => { p.string()?; Some(()) },
        b't' | b'f' | b'n' => {
            let word: &[u8] = match p.bytes[p.at] { b't' => b"true", b'f' => b"false", _ => b"null" };
            let end = p.at.checked_add(word.len())?;
            if p.bytes.get(p.at..end)? != word { return None; }
            p.at = end;
            Some(())
        },
        b'-' | b'0'..=b'9' => {
            if p.bytes[p.at] == b'-' { p.at += 1; }
            let first = *p.bytes.get(p.at)?;
            if !first.is_ascii_digit() { return None; }
            p.at += 1;
            if first != b'0' { while p.bytes.get(p.at).is_some_and(u8::is_ascii_digit) { p.at += 1; } }
            if p.bytes.get(p.at) == Some(&b'.') {
                p.at += 1;
                let start = p.at;
                while p.bytes.get(p.at).is_some_and(u8::is_ascii_digit) { p.at += 1; }
                if start == p.at { return None; }
            }
            if p.bytes.get(p.at).is_some_and(|v| matches!(*v, b'e' | b'E')) {
                p.at += 1;
                if p.bytes.get(p.at).is_some_and(|v| matches!(*v, b'+' | b'-')) { p.at += 1; }
                let start = p.at;
                while p.bytes.get(p.at).is_some_and(u8::is_ascii_digit) { p.at += 1; }
                if start == p.at { return None; }
            }
            Some(())
        },
        _ => None,
    }
}
fn simple_fields(p: &mut Parser<'_>) -> Option<[i64; 4]> {
    let mut fields = 0;
    let mut position = NO_POSITION;
    let mut fastest = ABSENT;
    let mut interval = ABSENT;
    let mut leader = ABSENT;
    let mut ahead = ABSENT;
    p.object(|p,key| {
        match key {
            b"Position" => { unique(&mut fields, 1)?; position = position_value(p)?; },
            b"TimeDiffToFastest" => { unique(&mut fields, 2)?; fastest = text_span(p)?; },
            b"IntervalToPositionAhead" => { unique(&mut fields, 4)?; interval = value_span(p)?; },
            b"GapToLeader" => { unique(&mut fields, 8)?; leader = value_span(p)?; },
            b"TimeDiffToPositionAhead" => { unique(&mut fields, 16)?; ahead = text_span(p)?; },
            _ => return None,
        }
        Some(())
    })?;
    Some([position, if interval != ABSENT {interval} else {fastest},
        if fastest != ABSENT {fastest} else {leader},
        if ahead != ABSENT {ahead} else {interval}])
}
pub fn tower_simple(bytes: &[u8], out: &mut [i64]) -> Option<usize> {
    if bytes.len() > MAX_RECORD || out.len() < TOWER_WORDS || !bytes.is_ascii() { return None; }
    let mut p = Parser { bytes, at: 0 };
    let mut root = 0;
    let mut rows = 0usize;
    let mut seen = [false; 1000];
    p.object(|p,key| {
        if key != b"Lines" { return None; }
        unique(&mut root, 1)?;
        p.object(|p,key| {
            let number = driver(key)?;
            if rows >= TOWER_ROWS || *seen.get(number)? { return None; }
            seen[number] = true;
            p.ws();
            let begin = p.at;
            // Simple fields are all exact, or the complete driver row is
            // validated and handed to the established lifecycle/sector engine.
            let (values, complex) = match simple_fields(p) {
                Some(values) => (values, ABSENT),
                None => {
                    p.at = begin;
                    if p.bytes.get(begin) != Some(&b'{') { return None; }
                    skip_value(p, 0)?;
                    ([NO_POSITION, ABSENT, ABSENT, ABSENT],
                        (i64::try_from(begin).ok()? << 32) | i64::try_from(p.at).ok()?)
                }
            };
            let at = 1 + rows * TOWER_FIELDS;
            out[at] = number as i64;
            out[at+1..at+5].copy_from_slice(&values);
            out[at+5] = complex;
            rows += 1;
            Some(())
        })
    })?;
    p.ws();
    if root != 1 || p.at != bytes.len() { return None; }
    out[0] = rows as i64;
    Some(1 + rows * TOWER_FIELDS)
}
#[cfg(test)]
mod tower_tests {
    use super::*;
    fn read_span(record: &[u8], span: i64) -> Option<&[u8]> {
        if span == ABSENT { return None; }
        record.get((span >> 32) as usize..(span & 0xffffffff) as usize)
    }
    #[test]
    fn tower_gap_precedence_and_explicit_empty_values() {
        let record = br#"{"Lines":{"44":{"Position":"2","TimeDiffToFastest":"+1.2","GapToLeader":{"Value":"3"},"IntervalToPositionAhead":{"Value":""},"TimeDiffToPositionAhead":"0.4"},"1":{"GapToLeader":{"Value":"+0.5"}}}}"#;
        let mut out = [0;TOWER_WORDS];
        assert_eq!(tower_simple(record,&mut out),Some(13));
        assert_eq!(&out[..3], &[2,44,2]);
        assert_eq!(read_span(record,out[3]),Some(b"".as_slice()));
        assert_eq!(read_span(record,out[4]),Some(b"+1.2".as_slice()));
        assert_eq!(read_span(record,out[5]),Some(b"0.4".as_slice()));
        assert_eq!(out[6], ABSENT);
        assert_eq!(&out[7..10], &[1,NO_POSITION,ABSENT]);
        assert_eq!(read_span(record,out[10]),Some(b"+0.5".as_slice()));
        assert_eq!(&out[11..13], &[ABSENT,ABSENT]);
    }
    #[test]
    fn mixed_records_preserve_complex_row_spans_and_simple_rows() {
        let record=br#"{"Lines":{"44":{"Position":"2"},"1":{"Retired":true,"Sectors":{"0":{"Value":"21.2","Segments":[{"Status":2048}]}}}}}"#;
        let mut out=[0;TOWER_WORDS];
        assert_eq!(tower_simple(record,&mut out),Some(13));
        assert_eq!(&out[..3], &[2,44,2]);
        assert_eq!(out[6],ABSENT);
        assert_eq!(out[7],1);
        assert_eq!(read_span(record,out[12]),Some(br#"{"Retired":true,"Sectors":{"0":{"Value":"21.2","Segments":[{"Status":2048}]}}}"#.as_slice()));
    }
    #[test]
    fn malformed_or_unsupported_envelopes_decline_transactionally() {
        let mut out=[0;TOWER_WORDS];
        for record in [
            br#"{"Lines":{"44":{"Position":"2"},"1":{"Retired":true,}}}"#.as_slice(),
            br#"{"Lines":{"44":{"Sectors":[1e]}}}"#,
            br#"{"Lines":{"44":{"GapToLeader":{"Value":"\u0031"}}}}"#,
            br#"{"Lines":{"44":{"Position":"2"},"44":{"Position":"3"}}}"#,
        ] { assert_eq!(tower_simple(record,&mut out),None); }
    }
}
