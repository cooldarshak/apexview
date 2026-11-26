import {quads,fitScene} from './receiver-model.mjs';
import {attachCastScene} from './cast-scene-controller.mjs';
const video=document.querySelector('#video'),canvas=document.querySelector('#scene'),status=document.querySelector('#status');
const gl=canvas.getContext('webgl',{alpha:false,preserveDrawingBuffer:false});
let scene=null,geometry=[],reported=false,failed=false,channel=null,loadReady=false;
let loadEpoch=0, frameSerial=0, uploadedFrame=null, videoCallback=null;
const hasVideoCallback=typeof video.requestVideoFrameCallback==='function';
function watchVideoFrame(epoch){
  if(!hasVideoCallback)return;
  videoCallback=video.requestVideoFrameCallback(()=>{
    if(epoch!==loadEpoch||failed)return;
    frameSerial++;watchVideoFrame(epoch);
  });
}
function resetFrameOwner(){
  loadEpoch++;frameSerial=0;uploadedFrame=null;loadReady=false;
  if(videoCallback!==null&&typeof video.cancelVideoFrameCallback==='function')
    video.cancelVideoFrameCallback(videoCallback);
  watchVideoFrame(loadEpoch);
}
video.addEventListener('loadeddata',()=>{loadReady=true;uploadedFrame=null;});
const message=text=>{status.textContent=text;status.hidden=!text;};
function shader(type,code){
  const s=gl.createShader(type);gl.shaderSource(s,code);gl.compileShader(s);
  if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error('Receiver shader compilation failed.');return s;
}
function initialize(){
  if(!gl)throw Error('The TV receiver does not support WebGL.');
  const precision=gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER,gl.HIGH_FLOAT)?.precision>0?'highp':'mediump';
  const p=gl.createProgram();
  gl.attachShader(p,shader(gl.VERTEX_SHADER,`attribute vec2 p;attribute vec2 uv;varying ${precision} vec2 t;void main(){gl_Position=vec4(p,0.,1.);t=uv;}`));
  gl.attachShader(p,shader(gl.FRAGMENT_SHADER,`precision ${precision} float;uniform sampler2D frame;varying ${precision} vec2 t;void main(){gl_FragColor=texture2D(frame,t);}`));
  gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw Error('Receiver shader linking failed.');
  gl.useProgram(p);gl.uniform1i(gl.getUniformLocation(p,'frame'),0);
  const texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
  for(const param of [gl.TEXTURE_MIN_FILTER,gl.TEXTURE_MAG_FILTER])gl.texParameteri(gl.TEXTURE_2D,param,gl.LINEAR);
  for(const param of [gl.TEXTURE_WRAP_S,gl.TEXTURE_WRAP_T])gl.texParameteri(gl.TEXTURE_2D,param,gl.CLAMP_TO_EDGE);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
  const attrs=[gl.getAttribLocation(p,'p'),gl.getAttribLocation(p,'uv')],buffers=[gl.createBuffer(),gl.createBuffer()];
  attrs.forEach(a=>gl.enableVertexAttribArray(a));
  return {texture,attrs,buffers};
}
let resources;
try{resources=initialize();}catch(error){failed=true;message(error.message);}
function render(){
  if(!failed&&scene&&loadReady&&video.readyState>=2){
    try{
      // Logical phone coordinates stay unchanged; the TV's graphics plane sets raster size.
      const fit=fitScene(scene.width,scene.height,innerWidth,innerHeight);
      for(const k in fit)canvas.style[k]=fit[k]+'px';
      const w=Math.max(1,Math.round(fit.width)),h=Math.max(1,Math.round(fit.height));
      if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
      gl.viewport(0,0,w,h);gl.clearColor(0,0,0,1);gl.clear(gl.COLOR_BUFFER_BIT);
      gl.bindTexture(gl.TEXTURE_2D,resources.texture);
      // Upload at video cadence, not display cadence. Keep drawing the cached
      // texture for geometry changes, including paused playback.
      const frame=hasVideoCallback?frameSerial:
        (video.getVideoPlaybackQuality?.().totalVideoFrames??video.currentTime);
      if(uploadedFrame!==frame){
        gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,video);
        uploadedFrame=frame;
      }
      for(const q of geometry){
        [q.positions,q.texture].forEach((values,i)=>{
          gl.bindBuffer(gl.ARRAY_BUFFER,resources.buffers[i]);gl.bufferData(gl.ARRAY_BUFFER,values,gl.DYNAMIC_DRAW);
          gl.vertexAttribPointer(resources.attrs[i],2,gl.FLOAT,false,0,0);
        });
        gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
      }
      if(gl.getError()!==gl.NO_ERROR)throw Error('The TV could not render this HEVC video texture.');
      if(!reported&&channel?.rendered(scene.sources)){reported=true;message('');}
    }catch(error){failed=true;message(error.message);channel?.failed(error.message);}
  }
  requestAnimationFrame(render);
}
const context=cast.framework.CastReceiverContext.getInstance();
channel=attachCastScene(context,cast.framework,video,(next,newLoad)=>{
  if(newLoad){reported=false;resetFrameOwner();}
  scene=next;geometry=quads(next).map(q=>({positions:new Float32Array(q.positions),texture:new Float32Array(q.texture)}));
  if(!reported)message('Loading video with the phone layout…');
},message);
canvas.addEventListener('webglcontextlost',event=>{event.preventDefault();failed=true;message('TV graphics context was lost. Reconnect Cast.');channel.failed('TV graphics context was lost.');});
render();
