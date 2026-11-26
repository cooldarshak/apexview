//! Allocation-free numerical processing shared by local and Cast overlay/audio
//! owners. History remains with the existing immutable JVM session model: the
//! Android bridge reads bounded regions, never duplicates full race histories.
pub const MAX_DRIVERS: usize = 64;
pub const MAX_HISTORY: usize = 4_194_304;
pub const MAX_GAP_MS: i64 = 5_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PositionCursor {
    pub lower: i32,
    pub previous: i64,
    pub seeks: i64,
}
impl Default for PositionCursor {
    fn default() -> Self {
        Self { lower: -1, previous: i64::MIN, seeks: 0 }
    }
}

/// Last timestamp <= now, including duplicate timestamp runs.
pub fn floor_index(n: usize, now: i64, mut time: impl FnMut(usize) -> Option<i64>) -> Option<i32> {
    if n > MAX_HISTORY { return None; }
    let (mut low, mut high) = (0usize, n);
    while low < high {
        let mid = low + (high - low) / 2;
        if time(mid)? <= now { low = mid + 1; } else { high = mid; }
    }
    Some(low as i32 - 1)
}

/// Use the previous published index only as a hint, never as historical truth.
/// Validate both neighbours, advance a bounded distance for normal playback,
/// and binary-search for seeks, stale hints or large clock jumps. This avoids
/// repeated Java timestamp reads without allocating or copying histories.
pub fn floor_index_hint(n: usize, now: i64, hint: i32, mut time: impl FnMut(usize) -> Option<i64>) -> Option<i32> {
    if n > MAX_HISTORY { return None; }
    if n == 0 { return Some(-1); }
    if hint >= -1 && (hint < 0 || (hint as usize) < n) &&
        (hint < 0 || time(hint as usize)? <= now) {
        let mut next = (hint + 1) as usize;
        for _ in 0..8 {
            if next == n || time(next)? > now { return Some(next as i32 - 1); }
            next += 1;
        }
    }
    floor_index(n, now, time)
}

/// Read at most two adjacent coordinates at once from an external history.
/// The position kernel asks for lower then upper; a one-point stack cache avoids
/// two additional JNI array-region calls on each interpolated driver.
pub fn adjacent_points(
    n: usize,
    mut read: impl FnMut(usize, &mut [f32], &mut [f32]) -> Option<()>,
) -> impl FnMut(usize) -> Option<(f32, f32)> {
    let mut next = None;
    move |at| {
        if at >= n { return None; }
        if let Some((index, point)) = next.take() {
            if at == index { return Some(point); }
        }
        let len = (n - at).min(2);
        let mut xs = [0.0; 2];
        let mut ys = [0.0; 2];
        read(at, &mut xs[..len], &mut ys[..len])?;
        if len == 2 { next = Some((at + 1, (xs[1], ys[1]))); }
        Some((xs[0], ys[0]))
    }
}

/// Mirrors OverlayPositionCursor's leading, end-of-history, gap, duplicate
/// and bounded chronological cursor rules. Outer None = invalid input;
/// inner None = legitimate missing position. No allocations or raw pointers.
pub fn position_at(
    n: usize, now: i64, cursor: &mut PositionCursor,
    mut time: impl FnMut(usize) -> Option<i64>,
    mut point: impl FnMut(usize) -> Option<(f32, f32)>,
) -> Option<Option<(f32, f32)>> {
    if n > MAX_HISTORY || cursor.seeks < 0 || cursor.lower < -1 ||
        cursor.lower >= 0 && cursor.lower as usize >= n { return None; }
    if n == 0 { return Some(None); }
    let first = time(0)?;
    let last = time(n - 1)?;
    if first > last { return None; }
    if now < first {
        cursor.previous = now; cursor.lower = 0;
        return Some(Some(point(0)?));
    }
    if now > last {
        cursor.previous = now;
        cursor.lower = n.saturating_sub(2) as i32;
        return Some(None);
    }
    if n == 1 {
        cursor.previous = now; cursor.lower = 0;
        return Some(Some(point(0)?));
    }
    let find_interval = |time: &mut dyn FnMut(usize) -> Option<i64>| -> Option<usize> {
        let (mut lo, mut hi) = (0usize, n - 1);
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            if time(mid)? <= now { lo = mid; } else { hi = mid; }
        }
        Some(lo)
    };
    let mut lower = if cursor.lower < 0 {
        find_interval(&mut time)?
    } else if now < cursor.previous {
        cursor.seeks = cursor.seeks.saturating_add(1);
        find_interval(&mut time)?
    } else {
        let mut lower = cursor.lower as usize;
        if lower > n - 2 { return None; }
        let mut advanced = 0;
        while lower < n - 2 && time(lower + 1)? <= now && advanced < 8 {
            lower += 1; advanced += 1;
        }
        if lower < n - 2 && time(lower + 1)? <= now {
            cursor.seeks = cursor.seeks.saturating_add(1);
            lower = find_interval(&mut time)?;
        }
        lower
    };
    lower = lower.min(n - 2);
    cursor.previous = now;
    cursor.lower = lower as i32;
    let lo = time(lower)?;
    let hi = time(lower + 1)?;
    let span = hi.checked_sub(lo)?;
    if span < 0 { return None; }
    if span > MAX_GAP_MS { return Some(None); }
    let f = if span == 0 { 0.0 } else { now.checked_sub(lo)? as f32 / span as f32 };
    let (x0, y0) = point(lower)?;
    let (x1, y1) = point(lower + 1)?;
    Some(Some((x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)))
}

/// Stable order: null positions are supplied as 99 by the caller; ties retain
/// original timing source order exactly. This only moves row indices.
pub fn row_order(positions: &[i32], out: &mut [i32]) -> Option<()> {
    if positions.len() > MAX_DRIVERS || out.len() != positions.len() { return None; }
    for (i, slot) in out.iter_mut().enumerate() { *slot = i as i32; }
    for i in 1..out.len() {
        let v = out[i];
        let mut j = i;
        while j > 0 && positions[out[j-1] as usize] > positions[v as usize] {
            out[j] = out[j-1]; j -= 1;
        }
        out[j] = v;
    }
    Some(())
}

/// Monotone cubic interpolation through measured Cast endpoints. Never
/// extrapolate or overshoot the two surrounding samples.
pub fn interpolate_motion(points:&[f32],steps:f32)->Option<(f32,f32)>{
    if !steps.is_finite()||!points.len().is_multiple_of(2)||
        !(4..=14).contains(&points.len())||points.iter().any(|v|!v.is_finite()){return None;}
    let last=points.len()/2-1;
    let progress=steps.clamp(0.0,last as f32);
    let i=(progress as usize).min(last-1);
    let fraction=progress-i as f32;
    let tangent=|before:f32,after:f32|if before==0.0||after==0.0||before*after<=0.0{0.0}else{2.0*before*after/(before+after)};
    let smooth=|axis:usize|{
        let a=points[2*i+axis];let b=points[2*(i+1)+axis];
        let p=points[2*i.saturating_sub(1)+axis];
        let q=points[2*(i+2).min(last)+axis];
        let slope=b-a;
        let m0=if i==0{slope}else{tangent(a-p,slope)};
        let m1=if i+1==last{slope}else{tangent(slope,q-b)};
        let t2=fraction*fraction;let t3=t2*fraction;
        ((2.0*t3-3.0*t2+1.0)*a+(t3-2.0*t2+fraction)*m0+
            (-2.0*t3+3.0*t2)*b+(t3-t2)*m1).clamp(a.min(b),a.max(b))
    };
    Some((smooth(0),smooth(1)))
}

/// Packed event histories: status(time,code), sectors(time,id,level),
/// safety-car-in(time), red intervals(start,end), resumptions(announced,at).
/// The caller owns the immutable histories; scratch is bounded by sector count.
pub fn flag_picture(
    status: &[i64], sectors: &[i64], safety: &[i64], red: &[i64],
    resumptions: &[i64], now: i64, out: &mut [i64],
) -> Option<()> {
    if !status.len().is_multiple_of(2) || !sectors.len().is_multiple_of(3) || !red.len().is_multiple_of(2) ||
        !resumptions.len().is_multiple_of(2) || out.len()!=67 { return None; }
    out.fill(0);
    let mut code=1;
    let mut since=0;
    for event in status.chunks_exact(2) {
        if event[0]>now {break;}
        if event[1]!=code {since=event[0];}
        code=event[1];
    }
    out[0]=if red.chunks_exact(2).any(|e| now>=e[0] && now<e[1]) {5} else {
        match code {
            4 if safety.iter().any(|&t| t>=since && t<=now)=>8,
            4..=7=>code,
            _=>1,
        }
    };
    for event in sectors.chunks_exact(3) {
        if event[0]>now {break;}
        let id=usize::try_from(event[1]).ok()?;
        if id>64 || !(0..=2).contains(&event[2]) {return None;}
        if id==0 {out[3..].fill(0);} else {out[2+id]=event[2];}
    }
    out[1] = -1;
    for (i,event) in resumptions.chunks_exact(2).enumerate().rev() {
        if event[0]<=now && now<event[1] && now.checked_sub(event[0])?<3_600_000 {
            out[1]=i as i64;break;
        }
    }
    Some(())
}

/// Admission is transactional for a fetched commentary batch. The platform
/// owner keeps the actual media bytes; no payload crosses JNI for validation.
pub fn audio_admit(pts_sizes: &[i64], last: i64, remaining: i64, slots: usize) -> Option<i64> {
    if !pts_sizes.len().is_multiple_of(2) || pts_sizes.len()/2>slots || remaining<0 {return None;}
    let mut previous=last;
    let mut bytes=0i64;
    for sample in pts_sizes.chunks_exact(2) {
        if sample[0]<0 || sample[0]<=previous || sample[1]<=0 {return None;}
        bytes=bytes.checked_add(sample[1])?;
        if bytes>remaining {return None;}
        previous=sample[0];
    }
    Some(bytes)
}

/// Chronological retirement reconciliation on already-normalized sparse
/// timing evidence. Packed scalar flags avoid array/object allocation and
/// never copy a driver's timing history across JNI.
/// Input: retired=1, correction pending=2, assertion=4, correction=8,
/// fresh current-lap event=16, crossing/pit exit=32, fresh sector=64.
/// Output: retired and pending correction, using the same low two bits.
pub fn retirement_state(flags:i32)->Option<i32> {
    if flags<0 || flags&!127!=0 {return None;}
    if flags&4!=0 {return Some(1);}
    if flags&1==0 {return Some(0);}
    let pending=flags&2!=0;
    if flags&16==0 {return Some(if pending {3} else {1});}
    let corrected=pending || flags&8!=0;
    if flags&32!=0 || corrected && flags&64!=0 {return Some(0);}
    Some(if corrected {3} else {1})
}

/// Three independent strips, each with at most 64 integer mini-sectors.
pub fn normalize_sectors(input:&[i32],counts:&[i32],out:&mut[i32])->Option<()> {
    if input.len()!=192||out.len()!=192||counts.len()!=6{return None;}
    for i in 0..3 {
        let n=usize::try_from(counts[2*i]).ok()?;
        let width=usize::try_from(counts[2*i+1]).ok()?;
        if n>width||width>64{return None;}
        let at=i*64;
        out[at..at+n].copy_from_slice(&input[at..at+n]);
        out[at+n..at+64].fill(0);
    }
    Some(())
}

/// MPEG-4 AAC-LC ADTS header, same supported rates/channels as existing Cast.
pub fn adts_header(payload_len: usize, sample_rate: i32, channels: i32) -> Option<[u8; 7]> {
    let rates = [96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350];
    let rate = rates.iter().position(|&r| r == sample_rate)? as u8;
    if !(1..=7).contains(&channels) || !(1..=8184).contains(&payload_len) { return None; }
    let channel = channels as usize;
    let length = payload_len + 7;
    Some([0xff,0xf1, (1 << 6) | (rate << 2) | (channel >> 2) as u8,
        (((channel & 3) << 6) | (length >> 11)) as u8,
        ((length >> 3) & 255) as u8, (((length & 7) << 5) | 31) as u8, 0xfc])
}

/// Exact signed truncation-toward-zero equivalent to JVM BigInteger scale.
pub fn scale_us(us: i64, timescale: i64) -> Option<i64> {
    i64::try_from((us as i128) * (timescale as i128) / 1_000_000).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn retirement_needs_chronological_progress_and_respects_assertions() {
        assert_eq!(retirement_state(4|16|32),Some(1));
        assert_eq!(retirement_state(1|8|16),Some(3));
        assert_eq!(retirement_state(3|64),Some(3));
        assert_eq!(retirement_state(1|16|64),Some(1));
        assert_eq!(retirement_state(3|16|64),Some(0));
        assert_eq!(retirement_state(1|16|32),Some(0));
        assert_eq!(retirement_state(3|4|16|64),Some(1));
        assert_eq!(retirement_state(0),Some(0));
        assert_eq!(retirement_state(-1),None);
        assert_eq!(retirement_state(128),None);
    }
    #[test]
    fn floor_preserves_duplicates_and_limits() {
        let t = [10, 10, 30, 30, 90];
        for (now, expected) in [(0,-1),(10,1),(29,1),(30,3),(90,4),(100,4)] {
            assert_eq!(floor_index(t.len(),now,|i|t.get(i).copied()),Some(expected));
        }
        assert_eq!(floor_index(0,0,|_|None),Some(-1));
        assert_eq!(floor_index(MAX_HISTORY+1,0,|_|None),None);
    }
    #[test]
    fn floor_hint_is_exact_for_forward_reverse_duplicates_and_invalid_hints() {
        let times: Vec<i64> = (0..1000).map(|i| i / 3 * 100).collect();
        let mut hint = -1;
        for now in (-100..40_000).step_by(17).chain([300,0,-100,10_000,33_300,42_000]) {
            let expected = floor_index(times.len(),now,|i|times.get(i).copied());
            let actual = floor_index_hint(times.len(),now,hint,|i|times.get(i).copied());
            assert_eq!(actual,expected);
            hint = actual.unwrap();
        }
        for hint in [-999,-1,0,50,999,1000,i32::MAX] {
            assert_eq!(floor_index_hint(times.len(),1234,hint,|i|times.get(i).copied()),
                floor_index(times.len(),1234,|i|times.get(i).copied()));
        }
        let mut reads = 0;
        assert_eq!(floor_index_hint(times.len(),1000,32,|i| {reads += 1; times.get(i).copied()}),Some(32));
        assert_eq!(reads,2);
        assert_eq!(floor_index_hint(0,0,123,|_|None),Some(-1));
        assert_eq!(floor_index_hint(MAX_HISTORY+1,0,-1,|_|None),None);
    }
    #[test]
    fn cursor_matches_reference_through_gaps_forward_and_seek() {
        let times: Vec<i64> = (0..50).map(|i| if i < 25 { i*200 } else { i*200+9000 }).collect();
        let points: Vec<_> = (0..50).map(|i| (i as f32*3.0, -(i as f32))).collect();
        let mut cursor = PositionCursor::default();
        let mut queries: Vec<_> = (-100..20_000).step_by(17).collect();
        queries.extend([0,100,14_100,100_000,400,2500,20_000,-100,0]);
        for now in queries {
            let expected = if now < times[0] { Some(points[0]) } else if now > *times.last().unwrap() { None } else {
                let mut i = 0;
                while i+1 < times.len()-1 && times[i+1] <= now { i += 1; }
                let span = times[i+1]-times[i];
                if span > 5000 { None } else {
                    let f = if span == 0 {0.0} else {(now-times[i]) as f32/span as f32};
                    Some((points[i].0+(points[i+1].0-points[i].0)*f, points[i].1+(points[i+1].1-points[i].1)*f))
                }
            };
            let actual = position_at(times.len(),now,&mut cursor,|i|times.get(i).copied(),|i|points.get(i).copied());
            assert_eq!(actual,Some(expected),"time={now}");
        }
        assert!(cursor.seeks>0);
        let mut one=PositionCursor::default();
        assert_eq!(position_at(1,1,&mut one,|_|Some(1),|_|Some((2.,3.))),Some(Some((2.,3.))));
        assert_eq!(position_at(1,2,&mut one,|_|Some(1),|_|Some((2.,3.))),Some(None));
    }
    #[test]
    fn adjacent_point_reads_are_bounded_and_preserve_cursor_samples() {
        let points = [(1.0, 2.0), (3.0, 4.0), (5.0, 6.0)];
        let reads = std::cell::Cell::new(0);
        let mut point = adjacent_points(points.len(), |at, x, y| {
            reads.set(reads.get() + 1);
            for (i, p) in points.get(at..at + x.len())?.iter().enumerate() {
                x[i] = p.0;
                y[i] = p.1;
            }
            Some(())
        });
        assert_eq!(point(0), Some(points[0]));
        assert_eq!(point(1), Some(points[1]));
        assert_eq!(reads.get(), 1);
        assert_eq!(point(2), Some(points[2]));
        assert_eq!(point(3), None);
        assert_eq!(point(1), Some(points[1]));
        assert_eq!(point(0), Some(points[0]));
        assert_eq!(reads.get(), 4);
        let mut cursor = PositionCursor::default();
        let times = [0, 100, 200];
        assert_eq!(position_at(3, 150, &mut cursor, |i| times.get(i).copied(),
            &mut point), Some(Some((4.0, 5.0))));
    }
    #[test]
    fn stable_order_and_audio_exact_bounds() {
        let mut out=[0;6];
        assert_eq!(row_order(&[99,3,1,3,99,1],&mut out),Some(()));
        assert_eq!(out,[2,5,1,3,0,4]);
        assert_eq!(adts_header(4,48000,2),Some([0xff,0xf1,0x4c,0x80,0x01,0x7f,0xfc]));
        assert!(adts_header(8184,7350,7).is_some());
        for (len,rate,ch) in [(0,48000,2),(8185,48000,2),(1,123,2),(1,48000,0),(1,48000,8)] {
            assert!(adts_header(len,rate,ch).is_none());
        }
        assert_eq!(scale_us(-1_234_567,90_000),Some(-111_111));
        assert_eq!(scale_us(i64::MAX,1_000_000),Some(i64::MAX));
        assert_eq!(scale_us(i64::MAX,i64::MAX),None);
    }
}
