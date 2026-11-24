/** Shared scene contract, independent of HTML/Cast SDKs. No playback URLs or F1 credentials. */
export function validateScene(s) {
  if (s?.version !== 1 || ![s.width,s.height,s.atlasWidth,s.atlasHeight,s.sources].every(v=>Number.isFinite(v)&&v>0) ||
      !Number.isInteger(s.sources) || s.sources>24 || !Array.isArray(s.windows) || s.windows.length>24) throw Error('Invalid scene');
  const columns=s.atlasColumns??s.sources,cw=s.sourceCodedWidth??s.atlasWidth/columns,ch=s.sourceCodedHeight??s.atlasHeight,
    dw=s.sourceDisplayWidth??cw,dh=s.sourceDisplayHeight??ch;
  if(!Number.isInteger(columns)||columns<1||columns>s.sources||![cw,ch,dw,dh].every(v=>Number.isFinite(v)&&v>0)||dw>cw||dh>ch)
    throw Error('Invalid atlas');
  const ids=new Set();
  for(const w of s.windows){
    if(ids.has(w.id)||!Number.isInteger(w.source)||w.source<0||w.source>=s.sources||
       ![w.x,w.y,w.width,w.height,w.z].every(Number.isFinite)||w.width<=0||w.height<=0)throw Error('Invalid video window');
    ids.add(w.id);
  }
  return s;
}
export function quads(scene){
  const s=validateScene(scene),dx=.5/s.atlasWidth,dy=.5/s.atlasHeight,columns=s.atlasColumns??s.sources,
    cw=s.sourceCodedWidth??s.atlasWidth/columns,ch=s.sourceCodedHeight??s.atlasHeight,
    dw=s.sourceDisplayWidth??cw,dh=s.sourceDisplayHeight??ch;
  return [...s.windows].sort((a,b)=>a.z-b.z).map(w=>{
    const col=w.source%columns,row=Math.floor(w.source/columns),u0=col*cw/s.atlasWidth+dx,u1=(col*cw+dw)/s.atlasWidth-dx,
      v0=1-(row*ch+dh)/s.atlasHeight+dy,v1=1-row*ch/s.atlasHeight-dy;
    if(u0<0||u1>1||v0<0||v1>1||u0>=u1||v0>=v1)throw Error('Atlas cell outside picture');
    return {id:w.id,
    positions:[2*w.x/s.width-1,1-2*(w.y+w.height)/s.height,2*(w.x+w.width)/s.width-1,1-2*(w.y+w.height)/s.height,
      2*w.x/s.width-1,1-2*w.y/s.height,2*(w.x+w.width)/s.width-1,1-2*w.y/s.height],
    texture:[u0,v0,u1,v0,u0,v1,u1,v1]};});
}
export function fitScene(w,h,viewW,viewH){const k=Math.min(viewW/w,viewH/h);return {width:w*k,height:h*k,left:(viewW-w*k)/2,top:(viewH-h*k)/2};}
