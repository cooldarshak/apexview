/** Shared scene contract, independent of HTML/Cast SDKs. No playback URLs or F1 credentials. */
export function validateScene(s) {
  if (s?.version !== 1 || ![s.width,s.height,s.atlasWidth,s.atlasHeight,s.sources].every(v=>Number.isFinite(v)&&v>0) ||
      !Number.isInteger(s.sources) || s.sources>16 || !Array.isArray(s.windows) || s.windows.length>16) throw Error('Invalid scene');
  const ids=new Set();
  for(const w of s.windows){
    if(ids.has(w.id)||!Number.isInteger(w.source)||w.source<0||w.source>=s.sources||
       ![w.x,w.y,w.width,w.height,w.z].every(Number.isFinite)||w.width<=0||w.height<=0)throw Error('Invalid video window');
    ids.add(w.id);
  }
  return s;
}
export function quads(scene){
  const s=validateScene(scene),dx=.5/s.atlasWidth,dy=.5/s.atlasHeight;
  return [...s.windows].sort((a,b)=>a.z-b.z).map(w=>({id:w.id,
    positions:[2*w.x/s.width-1,1-2*(w.y+w.height)/s.height,2*(w.x+w.width)/s.width-1,1-2*(w.y+w.height)/s.height,
      2*w.x/s.width-1,1-2*w.y/s.height,2*(w.x+w.width)/s.width-1,1-2*w.y/s.height],
    texture:[w.source/s.sources+dx,dy,(w.source+1)/s.sources-dx,dy,w.source/s.sources+dx,1-dy,(w.source+1)/s.sources-dx,1-dy]}));
}
export function fitScene(w,h,viewW,viewH){const k=Math.min(viewW/w,viewH/h);return {width:w*k,height:h*k,left:(viewW-w*k)/2,top:(viewH-h*k)/2};}
