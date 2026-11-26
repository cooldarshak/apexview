import assert from 'node:assert/strict';
import {attachCastScene,NAMESPACE} from '../web/cast/cast-scene-controller.mjs';
import {quads} from '../app/src/main/assets/cast/receiver-model.mjs';
let load,custom,options,accepted,mediaInfo,loads=0,errors=[],messages=[];
const video={};
const framework={messages:{MessageType:{LOAD:'LOAD'}},events:{EventType:{ERROR:'ERROR'}},system:{MessageType:{JSON:'JSON'}}};
const context={getPlayerManager:()=>({setMessageInterceptor:(type,cb)=>{load=cb;},addEventListener:()=>{},getMediaInformation:()=>mediaInfo}),
  addCustomMessageListener:(ns,cb)=>{assert.equal(ns,NAMESPACE);custom=cb;},
  sendCustomMessage:(ns,sender,message)=>messages.push(message),start:o=>{options=o;}};
const channel=attachCastScene(context,framework,video,(s,newLoad)=>{accepted=s;if(newLoad)loads++;},e=>errors.push(e));
assert.equal(options.mediaElement,video);assert.equal(options.customNamespaces[NAMESPACE],'JSON');
const scene={version:1,width:1920,height:1080,atlasWidth:2560,atlasHeight:1472,sources:4,
  atlasColumns:2,sourceCodedWidth:1280,sourceCodedHeight:736,sourceDisplayWidth:1280,sourceDisplayHeight:720,
  windows:[{id:10,source:2,x:0,y:0,width:1440,height:810,z:0},
    ...[0,1,3].map((source,i)=>({id:20+i,source,x:1440,y:i*270,width:480,height:270,z:1}))]};
const request={media:{customData:{pitwallScene:scene}}};assert.equal(load(request),request);
assert.equal(accepted,scene);
const q=quads(accepted);assert.equal(q.length,4);
assert.equal(q[0].positions[0],-1);assert.equal(q[0].positions[2],.5); // main on left
for(const side of q.slice(1))assert.equal(side.positions[0],.5); // all three in right strip
assert(q[0].texture[1]<.5); // main camera is source 2, lower atlas row, not source zero
const moved={...scene,windows:scene.windows.map((w,i)=>i?{...w,z:5-i,x:100*i}:w)};
custom({data:{type:'scene',scene:moved}});assert.equal(accepted,moved);assert.equal(errors.length,0);
channel.rendered(4);assert.equal(messages.at(-1).type,'rendering');
assert.equal(load({media:{customData:{pitwallScene:{...scene,sourceDisplayHeight:540}}}}),null);
assert.match(errors.at(-1),/720p/);
assert.equal(load({media:{customData:{pitwallScene:{...scene,windows:scene.windows.slice(1)}}}}),null);
assert.match(errors.at(-1),/every selected/);
custom({data:'{bad json'});assert.equal(messages.at(-1).type,'error');
// A new load must not acknowledge the previous media; obsolete scene updates
// must not replace the new atlas even if the source count is identical.
const nextRequest={media:{contentId:'replacement',customData:{pitwallScene:scene,playbackId:'load2'}}};
assert.equal(load(nextRequest),nextRequest);
assert.equal(loads,2);
mediaInfo={contentId:'previous'};
assert.equal(channel.rendered(4),false);
custom({data:{type:'scene',playbackId:'load1',scene:moved}});
assert.equal(accepted,scene);
custom({data:{type:'scene',playbackId:'load2',scene:moved}});
assert.equal(accepted,moved);
mediaInfo={contentId:'replacement'};
assert.equal(channel.rendered(4),true);
assert.equal(messages.at(-1).playbackId,'load2');
const baked={version:1,width:960,height:540,atlasWidth:960,atlasHeight:540,sources:1,
  atlasColumns:1,sourceCodedWidth:960,sourceCodedHeight:540,sourceDisplayWidth:960,sourceDisplayHeight:540,
  windows:[{id:0,source:0,x:0,y:0,width:960,height:540,z:0}]};
const bakedRequest={media:{contentId:'baked',customData:{pitwallBaked:true,pitwallScene:baked,playbackId:'baked3'}}};
assert.equal(load(bakedRequest),bakedRequest);
assert.equal(accepted,baked);
assert.equal(load({media:{customData:{pitwallBaked:true,pitwallScene:scene}}}),null);
assert.match(errors.at(-1),/one full picture/);
custom({data:{type:'scene',playbackId:'baked3',scene:moved}});
assert.equal(accepted,baked);
mediaInfo={contentId:'baked'};
assert.equal(channel.rendered(1),true);
assert.equal(messages.at(-1).playbackId,'baked3');
console.log('CAST_LAYOUT_RECEIVER_PASS: CAF load, main+three, source order, live geometry, quality floor, baked picture and load identity');
