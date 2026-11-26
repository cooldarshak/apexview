//! Clear baked AVC/AAC transport writer. No decoder, DRM or authorization.
//! All output belongs to the caller's unpublished exact-size fragment lease.
pub const MASK:u64=0x1ffffffff;
pub const PAT:[u8;13]=[0,0,0xb0,13,0,1,0xc1,0,0,0,1,0xf0,0];
pub const PMT:[u8;23]=[0,2,0xb0,23,0,1,0xc1,0,0,0xe1,0,0xf0,0,0x1b,0xe1,0,0xf0,0,0x0f,0xe1,1,0xf0,0];
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
        w.sample(0,7,false,false,|_,dst|{dst.fill(1);Some(())}).unwrap();
        assert_eq!(w.finish(),Some([1,1,6,1]));
        assert!(out.chunks_exact(188).all(|p|p[0]==0x47));
        assert_eq!(out[2*188+5],0x50);
    }
}
