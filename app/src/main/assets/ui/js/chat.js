(function(){
const O=window.OCM,h=O.h,bus=O.bus,S=O.S;
let msgsEl,jumpEl,dockEl,statusEl,ta,sendBtn,modelChip;
let stick=true;
const cur=()=>O.curSession();
const streamOn=()=>!!S.streaming;

O.chatInit=root=>{
  msgsEl=h('div',{id:'msgs',onscroll:()=>{stick=nearBottom();jumpEl.classList.toggle('hide',stick)}});
  jumpEl=h('button',{id:'jump',class:'btn sm hide',onclick:()=>scrollEnd(true)},O.icon('chevD'));
  dockEl=h('div',{id:'dock',class:'hide'});
  statusEl=h('div',{class:'status-line hide'});
  ta=h('textarea',{rows:1,placeholder:'Спросите что угодно…',oninput:onInput});
  sendBtn=h('button',{id:'send',onclick:onSend},O.icon('send'));
  modelChip=h('button',{class:'chip',onclick:()=>O.openModels()},O.icon('cpu'),h('span',{class:'lbl'}));
  const bar=h('div',{class:'cbar'},modelChip,h('div',{class:'grow'}),sendBtn);
  const comp=h('div',{id:'composer'},h('div',{class:'cbox'},ta),bar);
  const wrap=h('div',{style:{position:'relative',flex:1,minHeight:0,display:'flex',flexDirection:'column'}},msgsEl,jumpEl);
  root.append(wrap,dockEl,statusEl,comp);
  ta.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();onSend()}});
  bus.on('open',renderAll);
  bus.on('msg',d=>{if(d.sid===S.sid)renderAll()});
  bus.on('delta',d=>{if(d.sid!==S.sid||!S.streaming)return;const was=stick;paintStream();if(was)scrollEnd()});
  bus.on('stream',d=>{if(d.sid===S.sid){updateSend();if(!d.on)renderAll()}updateStatus()});
  bus.on('models',updateChip);
  bus.on('meta',updateChip);
  updateChip();renderAll();
};
const nearBottom=()=>msgsEl.scrollHeight-msgsEl.scrollTop-msgsEl.clientHeight<140;
const scrollEnd=f=>{requestAnimationFrame(()=>{msgsEl.scrollTop=msgsEl.scrollHeight;if(f){stick=true;jumpEl.classList.add('hide')}})};
function onInput(){ta.style.height='auto';ta.style.height=Math.min(ta.scrollHeight,140)+'px'}
function onSend(){
  if(streamOn()){O.cancelStream();return}
  const t=ta.value;ta.value='';onInput();O.send(t);
}
function updateSend(){
  sendBtn.className=streamOn()?'stop':'';
  sendBtn.innerHTML='';
  sendBtn.append(streamOn()?O.icon('stop'):O.icon('send'));
  sendBtn.title=streamOn()?'Остановить':'Отправить';
}
function updateChip(){
  const lbl=modelChip.querySelector('.lbl');if(!lbl)return;
  const name=(S.catalog||[]).find(m=>m.id===S.model);
  lbl.textContent=name?(name.name||S.model):(S.model||'модель');
}
function renderAll(){
  msgsEl.innerHTML='';
  const s=cur();
  if(!s||!s.messages.length){showEmpty()}
  else{
    const fr=document.createDocumentFragment();
    s.messages.forEach(m=>fr.append(buildMsg(m)));
    msgsEl.append(fr);
    if(S.streaming&&S.streaming.sid===s.id)paintStream();
  }
  scrollEnd(true);updateSend();updateStatus();
}
function showEmpty(){
  msgsEl.append(h('div',{class:'empty'},
    O.logo(44),h('b',{style:{color:'var(--fg)',fontSize:'17px'}},'OpenCode'),
    h('span',null,'Прямой доступ к бесплатным моделям OpenCode Zen.'),
    h('span',{class:'mut s'},'Без rootfs и локального сервера: запрос идёт с телефона напрямую в opencode.ai')));
}
function buildMsg(m){
  const el=h('div',{class:'msg '+(m.role==='user'?'user':'ai')});
  if(m.role==='user'){
    el.append(h('div',{class:'u-bub'},h('span',{class:'u-txt'},m.content)));
  }else{
    const body=h('div',{class:'a-body'});
    if(m.reasoning&&S.opts.thinking){
      body.append(h('details',{class:'think'},h('summary',null,'Размышления'),h('div',{class:'p-text'},'')));
      const d=body.lastChild.lastChild;d.innerHTML=O.md(m.reasoning);
    }
    if(m.error){
      body.append(h('div',{class:'err-box'},O.icon('warn'),h('div',null,h('b',null,'Не удалось получить ответ'),h('p',null,m.error),
        m.note?h('p',{class:'mut s'},m.note):null)));
    }else{
      const t=h('div',{class:'p-text'});t.innerHTML=O.md(m.content||'');O.enhance(t);body.append(t);
    }
    const meta=h('div',{class:'a-meta'});
    const bits=[];
    if(m.model)bits.push(shortModel(m.model));
    if(m.ms)bits.push((m.ms/1000).toFixed(1)+' с');
    if(m.usage&&m.usage.total_tokens)bits.push(O.fmtK(m.usage.total_tokens)+' ток.');
    meta.textContent=bits.filter(Boolean).join(' · ');
    el.append(body,meta);
  }
  el.oncontextmenu=e=>{e.preventDefault();msgMenu(m)};
  el._tapT=0;
  el.addEventListener('touchstart',()=>{el._tapT=Date.now()}, {passive:true});
  el.addEventListener('touchend',()=>{if(Date.now()-el._tapT>600)msgMenu(m)}, {passive:true});
  return el;
}
function shortModel(id){return String(id||'').replace('-free','').replace(/-/g,' ')}
function msgMenu(m){
  const items=[
    {icon:'copy',label:'Копировать текст',sub:'Ответ целиком',fn:()=>O.native.copy(m.content)},
    {icon:'refresh',label:'Переспросить',sub:'Отправить предыдущее сообщение ещё раз',fn:()=>{
      const s=cur();if(!s||streamOn())return;
      const lastUser=[...s.messages].reverse().find(x=>x.role==='user');
      if(lastUser)O.send(lastUser.content);
    }},
  ];
  if(m.role==='user')items.shift();
  O.actions('Сообщение',items);
}
function paintStream(){
  const st=S.streaming;if(!st)return;
  let el=msgsEl.querySelector('[data-id="'+st.id+'"]');
  if(!el){
    const e=msgsEl.querySelector('.empty');if(e)e.remove();
    el=h('div',{class:'msg ai streaming','data-id':st.id});
    const body=h('div',{class:'a-body'});
    const think=h('details',{class:'think hide'},h('summary',null,'Думает…'),h('div',{class:'p-text'}));
    const text=h('div',{class:'p-text'});
    const meta=h('div',{class:'a-meta'});
    body.append(think,text);el.append(body,meta);
    msgsEl.append(el);
  }
  const think=el.querySelector('.think'),text=el.querySelector('.p-text'),meta=el.querySelector('.a-meta');
  if(st.reasoning){think.classList.remove('hide');const d=think.lastChild;d.innerHTML=O.md(st.reasoning);think.open=true}
  text.innerHTML=O.md(st.content||'…');O.enhance(text);
  const secs=(Date.now()-st.started)/1000;
  meta.textContent=shortModel(st.model)+' · '+secs.toFixed(0)+' с';
}
function updateStatus(){
  const st=S.streaming;
  if(st&&st.sid===S.sid){
    statusEl.classList.remove('hide');
    statusEl.textContent='Поток идёт: '+st.model+' — можно свернуть, ответ сохранится';
  }else statusEl.classList.add('hide');
}
})();
