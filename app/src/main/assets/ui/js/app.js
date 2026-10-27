(function(){
const O=window.OCM,h=O.h,S=O.S,bus=O.bus;
let built=false,side=null,T={};
const wide=()=>innerWidth>=820;
function build(){
  if(built)return;built=true;const app=O.$('#app');
  T.menu=h('button',{class:'ibtn',onclick:()=>O.openSessions()},O.icon('menu'));
  T.t=h('b',null,'OpenCode');
  T.new=h('button',{class:'ibtn',onclick:()=>{const s=O.newSession();O.openSession(s.id)}},O.icon('plus'));
  T.set=h('button',{class:'ibtn',onclick:()=>O.openSettings()},O.icon('sliders'));
  const top=h('div',{id:'top'},T.menu,h('div',{class:'ttl grow'},T.t),T.new,T.set);
  T.banner=h('div',{id:'banner',class:'hide'});
  P.chat=h('div',{id:'pane-chat',class:'pane'});
  const main=h('div',{id:'main'},P.chat);
  app.append(top,T.banner,main);
  O.chatInit(P.chat);
  addEventListener('resize',O.debounce(()=>{},200));
}
const P={};
O.openSessions=()=>{
  const body=h('div',null);
  body.append(h('button',{class:'li pri',onclick:()=>{side.close();const s=O.newSession();O.openSession(s.id)}},O.icon('plus'),h('div',{class:'t'},h('b',null,'Новый чат'))));
  if(!S.sessions.length)body.append(h('p',{class:'mut',style:{padding:'12px'}},'Пока пусто — начните первый чат'));
  S.sessions.forEach(s=>{
    const on=s.id===S.sid;
    body.append(h('button',{class:'li'+(on?' on':''),onclick:()=>{side.close();O.openSession(s.id)}},
      O.icon('chat'),h('div',{class:'t'},h('b',null,s.title||'Новый чат'),h('span',null,new Date(s.ts).toLocaleString())),
      h('button',{class:'ibtn s',onclick:(e)=>{e.stopPropagation();delMenu(s)}},O.icon('dots'))));
  });
  if(S.sessions.length)body.append(h('button',{class:'li',onclick:()=>{
    O.confirm('Очистить всё','Удалить все чаты с этого устройства?','Удалить',true).then(ok=>{
      if(ok){O.clearAll();side.close()}
    });
  }},O.icon('trash'),h('div',{class:'t'},h('b',{class:'err'},'Удалить все чаты'))));
  side=O.sheet('Чаты',body);
};
function delMenu(s){
  O.actions(s.title||'Чат',[
    {icon:'edit',label:'Переименовать',fn:()=>O.ask('Название',null,s.title).then(v=>O.renameSession(s.id,v))},
    {icon:'trash',label:'Удалить чат',danger:true,fn:()=>O.confirm('Удалить чат',s.title||'','Удалить',true).then(ok=>{if(ok)O.deleteSession(s.id)})},
  ]);
}
O.back=()=>{return false}; // util.js already closes sheets via its own stack
function updateBanner(){
  if(!built)return;
  const t=navigator.onLine===false?'Нет сети — чаты доступны офлайн, ответы придут после подключения':'';
  T.banner.textContent=t;T.banner.classList.toggle('hide',!t);
}
addEventListener('online',updateBanner);addEventListener('offline',updateBanner);
// chat finished -> optional sound
bus.on('stream',d=>{if(!d.on&&S.opts.snd){try{O.native.testSound('done')}catch(e){}}});
document.addEventListener('DOMContentLoaded',()=>{
  O.saveOpts();build();
  bus.emit('boot');
});
})();
