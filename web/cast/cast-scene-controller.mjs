import {validateScene} from './receiver-model.mjs';
export const NAMESPACE='urn:x-cast:app.pitwall.scene';

/** CAF owns media playback and remote commands; this channel carries layout only. */
export function attachCastScene(context, framework, video, onScene, onError) {
  const manager=context.getPlayerManager();
  const report=(message,senderId)=>{
    const safe=String(message).replace(/https?:\/\/\S+/gi,'[URL]').slice(0,180);
    onError(safe);
    context.sendCustomMessage(NAMESPACE,senderId??undefined,{type:'error',message:safe});
  };
  const accept=value=>{
    const scene=validateScene(value);
    if((scene.sourceDisplayHeight??scene.sourceCodedHeight??scene.atlasHeight)<720)
      throw Error('Cast sources must be at least 720p.');
    if(scene.windows.length!==scene.sources||new Set(scene.windows.map(w=>w.source)).size!==scene.sources)
      throw Error('The scene must include every selected source exactly once.');
    onScene(scene);
  };
  manager.setMessageInterceptor(framework.messages.MessageType.LOAD,request=>{
    try { accept(request.media?.customData?.pitwallScene); return request; }
    catch(error){report(error.message,request.senderId);return null;}
  });
  context.addCustomMessageListener(NAMESPACE,event=>{
    try {
      const data=typeof event.data==='string'?JSON.parse(event.data):event.data;
      if(data?.type==='scene')accept(data.scene);
    } catch(error){report(error.message,event.senderId);}
  });
  manager.addEventListener(framework.events.EventType.ERROR,()=>report('Receiver media playback failed. Check HEVC support and the phone connection.'));
  context.start({mediaElement:video,customNamespaces:{[NAMESPACE]:framework.system.MessageType.JSON}});
  return {
    rendered:sources=>context.sendCustomMessage(NAMESPACE,undefined,{type:'rendering',sources}),
    failed:message=>report(message),
  };
}
