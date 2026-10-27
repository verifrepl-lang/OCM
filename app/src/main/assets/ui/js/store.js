(function(){
const O=window.OCM;
const bus=O.bus={h:{},on(n,f){(this.h[n]=this.h[n]||[]).push(f)},emit(n,...a){(this.h[n]||[]).forEach(f=>{try{f(...a)}catch(e){console.error(n,e)}})}};
// ---- app state: sessions live in localStorage, the model/key in the native side.
const S=O.S={
  sessions:[],        // [{id,title,model,ts,messages:[{role,content,reasoning,ms}]}]
  sid:null,
  streams:{},         // active streams by id: {content,reasoning,model,started}
  streaming:null,     // the latest stream (composer stop target)
  catalog:null,       // [{id,name,free}] from native
  model:null,
  hasKey:false,
  opts:Object.assign({thinking:true,fs:14,theme:'opencode',fallback:true},O.store.get('opts',{}))
};
O.saveOpts=()=>{O.store.set('opts',S.opts);document.documentElement.dataset.theme=S.opts.theme;document.documentElement.style.setProperty('--fs',S.opts.fs+'px')};

// ---- sessions ----
const KEY='sessions.v2';
O.loadSessions=()=>{S.sessions=O.store.get(KEY,[]);};
const persist=O.debounce(()=>{O.store.set(KEY,S.sessions.slice(0,60))},300);
O.persistSessions=persist;
O.curSession=()=>S.sessions.find(x=>x.id===S.sid)||null;
O.newSession=()=>{
  const s={id:'s'+Date.now().toString(36)+Math.random().toString(36).slice(2,7),title:'Новый чат',model:S.model,ts:Date.now(),messages:[]};
  S.sessions.unshift(s);persist();return s};
O.openSession=id=>{S.sid=id;bus.emit('open',id)};
O.deleteSession=id=>{S.sessions=S.sessions.filter(x=>x.id!==id);if(S.sid===id)S.sid=null;persist();bus.emit('sessions');if(!S.sid)bus.emit('open',null)};
O.renameSession=(id,title)=>{const s=S.sessions.find(x=>x.id===id);if(s){s.title=title||'Новый чат';persist();bus.emit('sessions')}};
O.clearAll=()=>{S.sessions=[];S.sid=null;persist();bus.emit('sessions');bus.emit('open',null)};

// ---- chat io ----
O.send=(text)=>{
  text=String(text||'').trim();if(!text)return;
  let s=O.curSession();
  if(!s){s=O.newSession();O.openSession(s.id)}
  s.messages.push({role:'user',content:text,ts:Date.now()});
  if(s.title==='Новый чат')s.title=text.slice(0,42)+(text.length>42?'…':'');
  s.ts=Date.now();persist();
  bus.emit('msg',{sid:s.id});
  startStream(s);
};
function history(s){
  // keep the last 24 turns so long chats stay fast
  return s.messages.slice(-24).map(m=>({role:m.role,content:m.content})).filter(m=>m.content);
}
function startStream(s){
  const id='st'+Date.now().toString(36)+Math.random().toString(36).slice(2,6);
  const st={id,sid:s.id,content:'',reasoning:'',model:S.model||'…',started:Date.now()};
  S.streams[id]=st;S.streaming=st;
  bus.emit('stream',{sid:s.id,on:true});
  const payload={id,model:S.model,messages:history(s)};
  const key=O.native.getKey();
  if(key)payload.key=key;
  else if(!S.opts.fallback)payload.model=S.model; // pinned, no fallback
  O.native.chat(JSON.stringify(payload));
}
O.cancelStream=()=>{
  const st=S.streaming;if(!st)return;
  O.native.chatCancel(st.id);
  delete S.streams[st.id];
  if(S.streaming===st)S.streaming=null;
  bus.emit('stream',{sid:st.sid,on:false});
  const s=S.sessions.find(x=>x.id===st.sid);
  if(s&&(st.content.trim()||st.reasoning)){
    s.messages.push({role:'assistant',content:st.content.trim(),reasoning:st.reasoning.trim(),model:st.model,ms:Date.now()-st.started,note:'остановлено'});
    s.ts=Date.now();persist();
  }
  bus.emit('msg',{sid:st.sid});
};
function finishStream(st,o){
  o=o||{};
  delete S.streams[st.id];
  if(S.streaming===st)S.streaming=null;
  bus.emit('stream',{sid:st.sid,on:false});
  const s=S.sessions.find(x=>x.id===st.sid);
  if(!s)return;
  const content=st.content.trim();
  if(content||st.reasoning){
    s.messages.push({role:'assistant',content:content,reasoning:st.reasoning.trim(),model:st.model,ms:Date.now()-st.started,
      usage:o.usage||null,note:o.note||''});
    s.ts=Date.now();persist();
  }else if(o.error){
    s.messages.push({role:'assistant',content:'',error:o.error,model:st.model,note:o.note||''});persist();
  }
  bus.emit('msg',{sid:st.sid});
}
// events from ZenClient (Kotlin)
O.onChat=d=>{
  const st=S.streams[d.id];if(!st)return;
  if(d.delta){st.content+=d.delta;bus.emit('delta',{sid:st.sid})}
  if(d.reasoning){st.reasoning+=d.reasoning;bus.emit('delta',{sid:st.sid})}
  if(d.model){st.model=d.model;bus.emit('delta',{sid:st.sid})}
  if(d.usage)st.usage=d.usage;
  if(d.done)finishStream(st,{});
  if(d.error)finishStream(st,{error:d.error,note:d.status?'HTTP '+d.status:''});
};
bus.on('boot',()=>{
  O.loadSessions();
  S.streams={};
  const st=O.native.state();
  S.model=st.model||O.store.get('model',null)||'glm-5.3-flash-free';
  if(st.model)O.store.set('model',st.model);
  S.hasKey=!!st.hasKey;
  O.native.models();
});
})();
