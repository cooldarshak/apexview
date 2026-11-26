import {validateScene} from './receiver-model.mjs';
export const NAMESPACE='urn:x-cast:app.pitwall.scene';

/** CAF owns media playback and remote commands; this channel carries layout only. */
export function attachCastScene(context, framework, video, onScene, onError) {
  const manager=context.getPlayerManager();
  let playbackId='', contentId='', baked=false;
  const report=(message,senderId,id=playbackId)=>{
    const safe=String(message).replace(/https?:\/\/\S+/gi,'[URL]').slice(0,180);
    onError(safe);
    context.sendCustomMessage(NAMESPACE,senderId??undefined,{type:'error',message:safe,playbackId:id});
  };
  const validate=(value,isBaked)=>{
    const scene=validateScene(value);
    if(!isBaked&&(scene.sourceDisplayHeight??scene.sourceCodedHeight??scene.atlasHeight)<720)
      throw Error('Cast sources must be at least 720p.');
    if(scene.windows.length!==scene.sources||new Set(scene.windows.map(w=>w.source)).size!==scene.sources)
      throw Error('The scene must include every selected source exactly once.');
    // Baked AVC is already the finished picture, not a set of 720p HEVC
    // source tiles. Never let the baked marker bypass the normal atlas gate.
    if(isBaked&&(scene.sources!==1||scene.windows[0].source!==0||
       scene.windows[0].x!==0||scene.windows[0].y!==0||
       scene.windows[0].width!==scene.width||scene.windows[0].height!==scene.height||
       scene.atlasWidth!==scene.width||scene.atlasHeight!==scene.height))
      throw Error('Baked Cast requires one full picture.');
    return scene;
  };
  manager.setMessageInterceptor(framework.messages.MessageType.LOAD,request=>{
    const data=request.media?.customData;
    const requestedId=typeof data?.playbackId==='string'&&data.playbackId.length<=80?data.playbackId:'';
    try {
      const isBaked=data?.pitwallBaked===true;
      const next=validate(data?.pitwallScene,isBaked);
      const id=data?.playbackId??'';
      if(typeof id!=='string'||id.length>80)throw Error('Invalid Cast playback identity.');
      playbackId=id; contentId=request.media?.contentId??''; baked=isBaked;
      onScene(next,true);
      return request;
    } catch(error){report(error.message,request.senderId,requestedId);return null;}
  });
  context.addCustomMessageListener(NAMESPACE,event=>{
    try {
      const data=typeof event.data==='string'?JSON.parse(event.data):event.data;
      if(data?.type==='scene'&&(!playbackId||data.playbackId===playbackId)&&!baked)
        onScene(validate(data.scene,false),false);
    } catch(error){report(error.message,event.senderId);}
  });
  manager.addEventListener(framework.events.EventType.ERROR,()=>report('Receiver media playback failed. Check HEVC support and the phone connection.'));
  context.start({mediaElement:video,customNamespaces:{[NAMESPACE]:framework.system.MessageType.JSON}});
  return {
    rendered:sources=>{
      // The CAF player must have adopted this exact media, not the previous
      // load whose final frame can remain visible during preparation.
      const current=manager.getMediaInformation();
      if(contentId&&current?.contentId!==contentId)return false;
      // Two loads may reuse a relay URL. Match CAF's adopted custom identity too.
      if(playbackId&&current?.customData?.playbackId!==playbackId)return false;
      context.sendCustomMessage(NAMESPACE,undefined,{type:'rendering',sources,playbackId});
      return true;
    },
    failed:message=>report(message),
  };
}
