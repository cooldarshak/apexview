//! Clear baked AVC/AAC transport writer. No decoder, DRM or authorization.
//! All output belongs to the caller's unpublished exact-size fragment lease.
pub const MASK:u64=0x1ffffffff;
pub const PAT:[u8;13]=[0,0,0xb0,13,0,1,0xc1,0,0,0,1,0xf0,0];
pub const PMT:[u8;23]=[0,2,0xb0,23,0,1,0xc1,0,0,0xe1,0,0xf0,0,0x1b,0xe1,0,0xf0,0,0x0f,0xe1,1,0xf0,0];
/// Validate one complete ADTS access unit, not just its sync bytes.
pub fn valid_adts(header:&[u8],len:usize)->bool{
    if header.len()<7 || header[0]!=255 || header[1]&0xf6!=0xf0{return false;}
    let header_len=if header[1]&1!=0{7}else{9};
    let declared=(usize::from(header[3]&3)<<11)|(usize::from(header[4])<<3)|usize::from(header[5]>>5);
    len>=header_len && declared==len && (header[2]>>2)&15<13
}
/// Check the generated transport envelope without allocating or reassembling PES.
/// Continuity is independently derived from the input counters.
pub fn verify_packets(out:&[u8],mut counters:[u8;4])->Option<[u8;4]>{
    if out.len()<4*188||!out.len().is_multiple_of(188)||counters.iter().any(|&v|v>15){return None;}
    let mut seen=[false;4];
    let mut audio_remaining=0usize;
    let mut last_pts=[None::<u64>;2];
    for (index,p) in out.chunks_exact(188).enumerate(){
        if p[0]!=0x47||p[1]&0x80!=0||p[3]&0xc0!=0{return None;}
        let pid=(u16::from(p[1]&31)<<8)|u16::from(p[2]);
        let slot=match pid{0=>0,0x1000=>1,0x100=>2,0x101=>3,_=>return None};
        if (index<2&&slot!=index)||(index>=2&&slot<2)||p[3]&15!=counters[slot]{return None;}
        counters[slot]=(counters[slot]+1)&15;
        let start=p[1]&0x40!=0;
        let at=match (p[3]>>4)&3{1=>4,3=>5+usize::from(p[4]),_=>return None};
        if at>=188||!seen[slot]&&!start{return None;}
        if start{
            if slot<2{
                let body=p.get(at..)?;
                if body.len()<5||body[0]!=0||body[1]!=if slot==0{0}else{2}{return None;}
                let end=4+((usize::from(body[2]&15)<<8)|usize::from(body[3]));
                let section=body.get(1..end)?;
                let expected=if slot==0{&PAT[..]}else{&PMT[..]};
                if body.get(..expected.len())?!=expected||end!=expected.len()+4{return None;}
                let mut crc=u32::MAX;
                for &b in section{crc^=u32::from(b)<<24;for _ in 0..8{crc=(crc<<1)^if crc&0x80000000!=0{0x04c11db7}else{0};}}
                if crc!=0{return None;}
            }else{
                let pes=p.get(at..at+14)?;
                if pes[..3]!=[0,0,1]||pes[3]!=if slot==2{0xe0}else{0xc0}||
                   pes[6]!=0x84||pes[7]!=0x80||pes[8]!=5||pes[9]&0xf1!=0x21||pes[11]&1==0||pes[13]&1==0{return None;}
                let pts=(u64::from(pes[9]&14)<<29)|(u64::from(pes[10])<<22)|
                    (u64::from(pes[11]&254)<<14)|(u64::from(pes[12])<<7)|u64::from(pes[13]>>1);
                if last_pts[slot-2].is_some_and(|prev|pts.wrapping_sub(prev)&MASK>MASK/2){return None;}
                last_pts[slot-2]=Some(pts);
                let length=usize::from(u16::from_be_bytes([pes[4],pes[5]]));
                if slot==2{
                    if length!=0||p[3]&0x30!=0x30||p[4]<7||p[5]&0x10==0||
                       !seen[slot]&&p[5]&0x40==0{return None;}
                    let pcr=(u64::from(p[6])<<25)|(u64::from(p[7])<<17)|
                        (u64::from(p[8])<<9)|(u64::from(p[9])<<1)|u64::from(p[10]>>7);
                    if p[10]&0x7f!=0x7e||p[11]!=0||pts.wrapping_sub(pcr)&MASK!=9000{return None;}
                    if at+14>=188{return None;}
                }else{
                    if audio_remaining!=0||length<15{return None;}
                    let payload=p.get(at+14..)?;
                    audio_remaining=length-8;
                    if !valid_adts(payload,audio_remaining)||payload.len()>audio_remaining{return None;}
                    audio_remaining-=payload.len();
                }
            }
        }else if slot==3{
            audio_remaining=audio_remaining.checked_sub(188-at)?;
        }
        seen[slot]=true;
    }
    (audio_remaining==0&&seen.iter().all(|&v|v)).then_some(counters)
}
pub struct Writer<'a>{out:&'a mut [u8],at:usize,continuity:[u8;4]}
impl<'a> Writer<'a>{
    pub fn new(out:&'a mut [u8],continuity:[u8;4])->Option<Self>{
        if !out.len().is_multiple_of(188)||continuity.iter().any(|&v|v>15){return None;}
        Some(Self{out,at:0,continuity})
    }
    pub fn tables(&mut self)->Option<()>{
        self.table(0,0,&PAT)?;
        self.table(0x1000,1,&PMT)
    }
    fn table(&mut self,pid:u16,slot:usize,section:&[u8])->Option<()>{
        let mut bytes=[0u8;32];
        let n=section.len();
        bytes.get_mut(..n)?.copy_from_slice(section);
        let mut crc=u32::MAX;
        for &b in &section[1..]{
            crc^=u32::from(b)<<24;
            for _ in 0..8{crc=(crc<<1)^if crc&0x80000000!=0{0x04c11db7}else{0};}
        }
        bytes[n..n+4].copy_from_slice(&crc.to_be_bytes());
        self.packetize(Packet{pid,slot,header:&bytes[..n+4],len:0,pcr:None,random:false},|_,_|None)
    }
    pub fn sample(&mut self,pts_us:i64,len:usize,video:bool,key:bool,
        copy:impl FnMut(usize,&mut [u8])->Option<()>)->Option<()>{
        if pts_us<0||len==0||!video&&len>65527{return None;}
        let ticks=crate::hot_path::scale_us(pts_us,90_000)? as u64;
        let pts=(ticks+9000)&MASK;
        let length=if video{0}else{len+8};
        let header=[0,0,1,if video{0xe0}else{0xc0},(length>>8)as u8,length as u8,0x84,0x80,5,
            0x21|((pts>>29)as u8&14),(pts>>22)as u8,1|((pts>>14)as u8&254),
            (pts>>7)as u8,1|((pts<<1)as u8&254)];
        self.packetize(Packet{pid:if video{0x100}else{0x101},slot:if video{2}else{3},
            header:&header,len,pcr:video.then_some(pts.wrapping_sub(9000)&MASK),random:video&&key},copy)
    }
    fn packetize(&mut self,p:Packet<'_>,mut copy:impl FnMut(usize,&mut [u8])->Option<()>)->Option<()>{
        let total=p.header.len().checked_add(p.len)?;
        let mut offset=0usize;
        while offset<total{
            let first=offset==0;
            let clock=p.pcr.filter(|_|first);
            let random=first&&p.random;
            let minimum=if clock.is_some(){8}else if random{2}else{0};
            let size=(total-offset).min(184-minimum);
            let adaptation=184-size;
            let end=self.at.checked_add(188)?;
            let packet=self.out.get_mut(self.at..end)?;
            packet[0]=0x47;packet[1]=(if first{0x40}else{0})|((p.pid>>8)as u8);packet[2]=p.pid as u8;
            packet[3]=(if adaptation==0{0x10}else{0x30})|self.continuity[p.slot];
            self.continuity[p.slot]=(self.continuity[p.slot]+1)&15;
            let mut at=4usize;
            if adaptation>0{
                packet[at]=(adaptation-1)as u8;at+=1;
                if adaptation>1{
                    packet[at]=(if clock.is_some(){0x10}else{0})|(if random{0x40}else{0});at+=1;
                    if let Some(c)=clock{
                        packet[at..at+6].copy_from_slice(&[(c>>25)as u8,(c>>17)as u8,
                            (c>>9)as u8,(c>>1)as u8,(((c&1)<<7)|0x7e)as u8,0]);at+=6;
                    }
                    packet[at..4+adaptation].fill(255);at=4+adaptation;
                }
            }
            let prefix=size.min(p.header.len().saturating_sub(offset));
            if prefix>0{packet[at..at+prefix].copy_from_slice(&p.header[offset..offset+prefix]);}
            if size>prefix{copy(offset+prefix-p.header.len(),&mut packet[at+prefix..at+size])?;}
            self.at=end;offset+=size;
        }
        Some(())
    }
    pub fn finish(self)->Option<[u8;4]>{(self.at==self.out.len()).then_some(self.continuity)}
}
struct Packet<'a>{pid:u16,slot:usize,header:&'a [u8],len:usize,pcr:Option<u64>,random:bool}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn packets_use_exact_size_and_continuity(){
        let payload=[0x77u8;1000];let mut out=[0u8;188*9];
        let mut w=Writer::new(&mut out,[0;4]).unwrap();
        w.tables().unwrap();
        w.sample(0,1000,true,true,|at,dst|{dst.copy_from_slice(payload.get(at..at+dst.len())?);Some(())}).unwrap();
        let audio=[0xff,0xf1,0x4c,0x80,1,0x1f,0xfc,0];
        w.sample(0,audio.len(),false,false,|at,dst|{dst.copy_from_slice(audio.get(at..at+dst.len())?);Some(())}).unwrap();
        assert_eq!(w.finish(),Some([1,1,6,1]));
        assert!(out.chunks_exact(188).all(|p|p[0]==0x47));
        assert_eq!(out[2*188+5],0x50);
        assert_eq!(verify_packets(&out,[0;4]),Some([1,1,6,1]));
        let mut damaged=out;
        damaged[2*188+3]^=1;
        assert_eq!(verify_packets(&damaged,[0;4]),None);
        let mut damaged=out;
        let at=2*188+5+usize::from(damaged[2*188+4]);
        damaged[at+2]=0;
        assert_eq!(verify_packets(&damaged,[0;4]),None);
        let mut damaged=out;
        damaged[187]^=1;
        assert_eq!(verify_packets(&damaged,[0;4]),None);
    }
    #[test]
    fn packet_verification_rejects_invalid_pes_length_clock_and_continuation() {
        let audio=[0xff,0xf1,0x4c,0x80,0x20,0x1f,0xfc]; // 256-byte ADTS unit
        let mut a=vec![0x55;256]; a[..7].copy_from_slice(&audio);
        assert!(valid_adts(&a,a.len()));
        let mut out=[0u8;188*5];
        let initial=[15,15,15,15];
        let mut w=Writer::new(&mut out,initial).unwrap();
        w.tables().unwrap();
        w.sample(100_000,8,true,true,|_,dst|{dst.fill(0x77);Some(())}).unwrap();
        w.sample(100_000,a.len(),false,false,|at,dst|{dst.copy_from_slice(a.get(at..at+dst.len())?);Some(())}).unwrap();
        assert_eq!(w.finish(),Some([0,0,0,1]));
        assert_eq!(verify_packets(&out,initial),Some([0,0,0,1]));
        let mut bad=out;
        bad[2*188+6]^=1; // PCR no longer agrees with PTS
        assert_eq!(verify_packets(&bad,initial),None);
        let mut bad=out;
        bad[3*188+9]^=1; // PES length byte in full first audio packet
        assert_eq!(verify_packets(&bad,initial),None);
        assert_eq!(verify_packets(&out[..4*188],initial),None); // truncated audio
    }

    #[test]
    fn timestamp_wrap_preserves_monotonic_order_and_rejects_backwards_pts() {
        // PTS is offset by 9000 ticks. A 40ms step straddles the 33-bit wrap.
        let before=((MASK-9000-1800)*100/9) as i64;
        let after=before+40_000;
        let audio=[0xff,0xf1,0x4c,0x80,1,0x1f,0xfc,0];
        for (first,second,valid) in [(before,after,true),(after,before,false)] {
            let mut out=[0u8;188*6];
            let initial=[15;4];
            let mut w=Writer::new(&mut out,initial).unwrap();
            w.tables().unwrap();
            for (pts,key) in [(first,true),(second,false)] {
                w.sample(pts,8,true,key,|_,dst|{dst.fill(0x77);Some(())}).unwrap();
                w.sample(pts,audio.len(),false,false,|at,dst|{
                    dst.copy_from_slice(audio.get(at..at+dst.len())?);Some(())
                }).unwrap();
            }
            let final_counters=w.finish().unwrap();
            assert_eq!(final_counters,[0,0,1,1]);
            assert_eq!(verify_packets(&out,initial),if valid{Some(final_counters)}else{None});
        }
    }

    #[test]
    fn complete_adts_extent_is_required(){
        let good=[0xff,0xf1,0x4c,0x80,1,0x1f,0xfc,0];
        assert!(valid_adts(&good,8));
        assert!(!valid_adts(&good,9));
        assert!(!valid_adts(&good[..6],8));
    }
}
