import assert from 'node:assert/strict';
import {validateScene,quads,fitScene} from '../app/src/main/assets/cast/receiver-model.mjs';
const base={version:1,width:1920,height:1080,atlasWidth:2880,atlasHeight:540,sources:3,windows:[
 {id:1,source:2,x:0,y:0,width:1280,height:720,z:3},
 {id:2,source:0,x:100,y:100,width:640,height:360,z:9},
 {id:3,source:1,x:-100,y:720,width:640,height:360,z:0}]};
assert.equal(validateScene(base),base);
const q=quads(base);assert.deepEqual(q.map(x=>x.id),[3,1,2]);
assert(q[0].positions[0]<-1);assert(q[1].texture[0]>2/3);assert(q[2].texture[2]<1/3);
assert.deepEqual(fitScene(1080,1920,1920,1080),{width:607.5,height:1080,left:656.25,top:0});
assert.deepEqual(fitScene(1920,1080,1920,1080),{width:1920,height:1080,left:0,top:0});
for(const key of ['width','height','atlasWidth','atlasHeight','sources'])for(const value of [0,-1,NaN,Infinity])assert.throws(()=>validateScene({...base,[key]:value}));
assert.throws(()=>validateScene({...base,windows:[base.windows[0],base.windows[0]]}));
assert.throws(()=>validateScene({...base,windows:[{...base.windows[0],source:3}]}));
const six={...base,sources:6,atlasWidth:3840,windows:Array.from({length:6},(_,i)=>({id:i,source:i,x:(i%3)*640,y:Math.floor(i/3)*360,width:640,height:360,z:i}))};
assert.equal(quads(six).length,6); // Protocol capacity, not a claim of six-feed device decoding.
const rows={...base,atlasWidth:960,atlasHeight:542,sources:4,atlasColumns:2,sourceCodedWidth:480,
 sourceCodedHeight:272,sourceDisplayWidth:480,sourceDisplayHeight:270,
 windows:Array.from({length:4},(_,i)=>({id:i,source:i,x:0,y:0,width:100,height:100,z:i}))};
const rowQuads=quads(rows);
assert(rowQuads[0].texture[1]>rowQuads[2].texture[5]); // each row excludes the coded padding
assert(Math.abs(rowQuads[2].texture[1]-.5/542)<1e-9);
assert(rowQuads[3].texture[0]>.5);
console.log('CAST_SCENE_PROTOCOL_PASS: layout, crop boundaries, overlap, portrait and multi-row geometry');
