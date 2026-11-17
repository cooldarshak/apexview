import {validateScene,quads,fitScene} from './receiver-model.mjs';
const video=document.querySelector('#video'),canvas=document.querySelector('#videoScene'),ui=document.querySelector('#ui'),stage=document.querySelector('#stage'),status=document.querySelector('#status');
const gl=canvas.getContext('webgl',{alpha:false,preserveDrawingBuffer:false});
let scene=null,hls=null,revision=-1,loading=false,overlayUrl=null,started=false,misses=0;
function message(s){status.textContent=s;status.hidden=!s;}
function shader(type,code){const s=gl.createShader(type);gl.shaderSource(s,code);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error('Receiver shader unavailable');return s;}
if(!gl)message('This receiver has no WebGL support.');
const program=gl?.createProgram();
if(gl){gl.attachShader(program,shader(gl.VERTEX_SHADER,'attribute vec2 p;attribute vec2 uv;varying vec2 t;void main(){gl_Position=vec4(p,0.,1.);t=uv;}'));
 gl.attachShader(program,shader(gl.FRAGMENT_SHADER,'precision mediump float;uniform sampler2D frame;varying vec2 t;void main(){gl_FragColor=texture2D(frame,t);}'));
 gl.linkProgram(program);gl.useProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error('Receiver program unavailable');}
const texture=gl?.createTexture(),positions=gl?.createBuffer(),coordinates=gl?.createBuffer();
if(gl){gl.bindTexture(gl.TEXTURE_2D,texture);for(const p of [gl.TEXTURE_MIN_FILTER,gl.TEXTURE_MAG_FILTER])gl.texParameteri(gl.TEXTURE_2D,p,gl.LINEAR);
 for(const p of [gl.TEXTURE_WRAP_S,gl.TEXTURE_WRAP_T])gl.texParameteri(gl.TEXTURE_2D,p,gl.CLAMP_TO_EDGE);gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);}
function render(){
 if(gl&&scene&&video.readyState>=2){
  if(canvas.width!==scene.width||canvas.height!==scene.height){canvas.width=scene.width;canvas.height=scene.height;}
  const fit=fitScene(scene.width,scene.height,innerWidth,innerHeight);for(const k in fit)stage.style[k]=fit[k]+'px';
  gl.viewport(0,0,canvas.width,canvas.height);gl.clearColor(0,0,0,1);gl.clear(gl.COLOR_BUFFER_BIT);
  gl.bindTexture(gl.TEXTURE_2D,texture);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,video);
  for(const q of quads(scene)){
   for(const [name,buffer,data]of [['p',positions,q.positions],['uv',coordinates,q.texture]]){const a=gl.getAttribLocation(program,name);gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(data),gl.DYNAMIC_DRAW);gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,2,gl.FLOAT,false,0,0);}
   gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
  }
 }
 requestAnimationFrame(render);
}
async function play(){try{await video.play();message('');}catch{status.hidden=false;const b=Object.assign(document.createElement('button'),{textContent:'Play on TV',onclick:play});status.replaceChildren(b);b.focus();}}
function load(){
 if(!gl||!globalThis.Hls?.isSupported()||!globalThis.MediaSource?.isTypeSupported('video/mp4; codecs="'+scene.codec+'"')){message('This receiver does not support the composed HEVC stream. Use a compatible HEVC receiver.');return;}
 hls?.destroy();hls=new Hls({startPosition:scene.positionMs/1000,maxBufferLength:12,maxMaxBufferLength:24,enableWorker:true});
 hls.on(Hls.Events.ERROR,(_,e)=>{if(e.fatal)message('Receiver playback failed: '+e.details);});
 hls.on(Hls.Events.MANIFEST_PARSED,()=>{video.playbackRate=scene.speed;video.muted=scene.muted;if(scene.playing)play();});
 hls.loadSource('master.m3u8');hls.attachMedia(video);started=true;
}
async function poll(){
 if(loading)return;loading=true;
 try{
  const next=validateScene(await(await fetch('scene.json',{cache:'no-store'})).json());misses=0;
  const previous=scene;scene=next;
  if(!started)load();
  if(revision!==scene.revision){
   if(previous&&previous.audioRevision!==scene.audioRevision){load();}
   else if(video.readyState>=1&&Math.abs(video.currentTime-scene.positionMs/1000)>.75)video.currentTime=scene.positionMs/1000;
   video.playbackRate=scene.speed;video.muted=scene.muted;
   if(scene.playing)play();else video.pause();revision=scene.revision;
  }
  const r=await fetch('overlay.png?t='+Math.round(video.currentTime*1000),{cache:'no-store'});
  if(r.ok){const u=URL.createObjectURL(await r.blob());const old=overlayUrl;overlayUrl=u;ui.src=u;if(old)URL.revokeObjectURL(old);}
 }catch{misses++;if(started&&misses>=3){video.pause();hls?.destroy();hls=null;started=false;message('Casting stopped or the phone disconnected.');}else if(!started)message('Waiting for the phone layout. Keep ApexView open on the phone.');}
 finally{loading=false;}
}
setInterval(poll,500);poll();render();
