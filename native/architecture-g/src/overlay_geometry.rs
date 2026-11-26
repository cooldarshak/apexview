//! Retained native circuit index encoded in caller-owned immutable storage.
//! Setup may allocate bounded scratch; queries allocate nothing. The packet
//! contains only public geometry, not media or credentials.
pub const MAX_VERTICES:usize=16_384;
const HEADER:usize=8;
pub fn packet_size(n:usize)->Option<usize>{
    if n>MAX_VERTICES{return None;}
    HEADER.checked_add(n.checked_mul(16)?)
}
fn read_u32(bytes:&[u8],at:usize)->Option<u32>{
    Some(u32::from_le_bytes(bytes.get(at..at.checked_add(4)?)?.try_into().ok()?))
}
fn write_u32(bytes:&mut [u8],at:usize,v:u32)->Option<()>{
    bytes.get_mut(at..at.checked_add(4)?)?.copy_from_slice(&v.to_le_bytes());Some(())
}
fn dist(dx:f32,dy:f32)->f32{(f64::from(dx).hypot(f64::from(dy))) as f32}
fn organize(ids:&mut [usize],xy:&[f32],axis:usize){
    if ids.len()<2{return;}
    let mid=ids.len()/2;
    ids.select_nth_unstable_by(mid,|a,b|xy[2*(*a)+axis].total_cmp(&xy[2*(*b)+axis]).then(a.cmp(b)));
    let (left,rest)=ids.split_at_mut(mid);
    organize(left,xy,axis^1);organize(&mut rest[1..],xy,axis^1);
}
pub fn prepare(xy:&[f32],packet:&mut [u8])->Option<()>{
    if !xy.len().is_multiple_of(2)||xy.iter().any(|v|!v.is_finite()){return None;}
    let n=xy.len()/2;
    if packet.len()!=packet_size(n)?{return None;}
    let mut ids=Vec::new();
    ids.try_reserve_exact(n).ok()?;
    ids.extend(0..n);
    organize(&mut ids,xy,0);
    write_u32(packet,0,n as u32)?;
    let mut along=0f32;
    for i in 0..n{
        if i>0{along+=dist(xy[2*i]-xy[2*i-2],xy[2*i+1]-xy[2*i-1]);}
        let at=HEADER+12*i;
        write_u32(packet,at,xy[2*i].to_bits())?;
        write_u32(packet,at+4,xy[2*i+1].to_bits())?;
        write_u32(packet,at+8,along.to_bits())?;
    }
    let total=if n>1{along+dist(xy[2*n-2]-xy[0],xy[2*n-1]-xy[1])}else{0.0};
    if !total.is_finite(){return None;}
    write_u32(packet,4,total.to_bits())?;
    for (i,id) in ids.into_iter().enumerate(){write_u32(packet,HEADER+12*n+4*i,id as u32)?;}
    Some(())
}

pub struct Geometry<'a>{packet:&'a [u8],n:usize,length:f32}
impl<'a> Geometry<'a>{
    pub fn new(packet:&'a [u8])->Option<Self>{
        let n=read_u32(packet,0)? as usize;
        let length=f32::from_bits(read_u32(packet,4)?);
        if packet.len()!=packet_size(n)?||!length.is_finite()||length<0.0{return None;}
        Some(Self{packet,n,length})
    }
    fn vertex(&self,i:usize)->Option<(f32,f32,f32)>{
        if i>=self.n{return None;}
        let at=HEADER+12*i;
        Some((f32::from_bits(read_u32(self.packet,at)?),f32::from_bits(read_u32(self.packet,at+4)?),
              f32::from_bits(read_u32(self.packet,at+8)?)))
    }
    fn search(&self,lo:usize,hi:usize,axis:usize,query:(f32,f32),best:&mut (usize,f32))->Option<()>{
        if lo==hi{return Some(());}
        let mid=lo+(hi-lo)/2;
        let index=read_u32(self.packet,HEADER+12*self.n+4*mid)? as usize;
        let (px,py,_)=self.vertex(index)?;
        let dx=px-query.0;let dy=py-query.1;
        let d=dx*dx+dy*dy;
        if d<best.1||d==best.1&&index<best.0{*best=(index,d);}
        let side=if axis==0{-dx}else{-dy};
        if side<0.0{
            self.search(lo,mid,axis^1,query,best)?;
            if side*side<=best.1{self.search(mid+1,hi,axis^1,query,best)?;}
        }else{
            self.search(mid+1,hi,axis^1,query,best)?;
            if side*side<=best.1{self.search(lo,mid,axis^1,query,best)?;}
        }
        Some(())
    }
    pub fn nearest(&self,x:f32,y:f32)->Option<usize>{
        if !x.is_finite()||!y.is_finite(){return None;}
        let mut best=(0,f32::MAX);
        self.search(0,self.n,0,(x,y),&mut best)?;
        Some(best.0)
    }
    pub fn progress(&self,x:f32,y:f32)->Option<f32>{
        if self.n<2||self.length==0.0{return Some(0.0);}
        let i=self.nearest(x,y)?;
        let mut best=self.vertex(i)?.2;
        let mut best_d=f32::MAX;
        for j in [(i+self.n-1)%self.n,i]{
            let k=(j+1)%self.n;
            let (ax,ay,start)=self.vertex(j)?;
            let (bx,by,end)=self.vertex(k)?;
            let vx=bx-ax;let vy=by-ay;
            let len2=vx*vx+vy*vy;
            let t=if len2==0.0{0.0}else{(((x-ax)*vx+(y-ay)*vy)/len2).clamp(0.0,1.0)};
            let px=ax+vx*t;let py=ay+vy*t;
            let d=(px-x)*(px-x)+(py-y)*(py-y);
            if d<best_d{
                best_d=d;
                best=start+if k==0{dist(vx,vy)*t}else{(end-start)*t};
            }
        }
        let f=(best/self.length).rem_euclid(1.0);
        if f.is_finite(){Some(f)}else{None}
    }
    pub fn point_at(&self,f:f32)->Option<[f32;4]>{
        if !f.is_finite(){return None;}
        if self.n<2||self.length==0.0{return Some([0.0,0.0,1.0,0.0]);}
        let target=f.rem_euclid(1.0)*self.length;
        let (mut low,mut high)=(1,self.n);
        while low<high{
            let mid=low+(high-low)/2;
            let end=if mid==self.n{self.length}else{self.vertex(mid)?.2};
            if end<target{low=mid+1;}else{high=mid;}
        }
        let i=low.saturating_sub(1).min(self.n-1);
        let j=(i+1)%self.n;
        let (ax,ay,start)=self.vertex(i)?;
        let (bx,by,end)=self.vertex(j)?;
        let span=(if j==0{self.length}else{end})-start;
        let k=if span==0.0{0.0}else{((target-start)/span).clamp(0.0,1.0)};
        let dx=bx-ax;let dy=by-ay;
        let len=dist(dx,dy).max(0.001);
        Some([ax+dx*k,ay+dy*k,dx/len,dy/len])
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn ties_and_exact_square_geometry(){
        let points=[0.,0.,100.,0.,100.,100.,0.,100.];
        let mut packet=vec![0;packet_size(4).unwrap()];
        assert_eq!(prepare(&points,&mut packet),Some(()));
        let g=Geometry::new(&packet).unwrap();
        assert_eq!(g.nearest(50.,0.),Some(0));
        assert_eq!(g.progress(50.,0.),Some(0.125));
        assert_eq!(g.progress(100.,50.),Some(0.375));
        assert_eq!(g.point_at(0.25),Some([100.,0.,1.,0.]));
        assert_eq!(g.point_at(0.5),Some([100.,100.,0.,1.]));
        assert_eq!(g.point_at(1.),Some([0.,0.,1.,0.]));
        assert!(Geometry::new(&packet[..packet.len()-1]).is_none());
    }
    #[test]
    fn balanced_search_matches_exhaustive_for_varied_points(){
        let mut seed=888u32;
        let mut next=||{seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);(seed%1000) as f32};
        let points:Vec<_>=(0..1200).map(|_|next()).collect();
        let mut packet=vec![0;packet_size(points.len()/2).unwrap()];
        prepare(&points,&mut packet).unwrap();
        let g=Geometry::new(&packet).unwrap();
        for _ in 0..2000{
            let (x,y)=(next(),next());
            let expected=points.chunks_exact(2).enumerate().min_by(|a,b|{
                let d=|p:&[f32]|(p[0]-x)*(p[0]-x)+(p[1]-y)*(p[1]-y);
                d(a.1).total_cmp(&d(b.1)).then(a.0.cmp(&b.0))
            }).unwrap().0;
            assert_eq!(g.nearest(x,y),Some(expected));
        }
    }
}
