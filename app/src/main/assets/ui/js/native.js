(function(){
const O=window.OCM;
// ---- native bridge: no local server, no http/sse/fs. Just chat + platform bits.
O.native={
  copy:t=>{try{OC.copy(t);O.toast('Скопировано',1200)}catch{}},
  share:t=>OC.share(t),
  open:u=>OC.openLink(u),
  paste:()=>{try{return OC.pasteText()}catch{return''}},
  state:()=>{try{return JSON.parse(OC.state())||{}}catch{return{}}},
  chat:p=>OC.chat(p),
  chatCancel:id=>OC.chatCancel(id),
  models:()=>OC.models(),
  setModel:m=>OC.setModel(m),
  setKey:k=>OC.setKey(k),
  getKey:()=>OC.getKey()||'',
  minimize:()=>OC.minimize(),
  grant:k=>OC.grant(k),
  grantInstall:()=>OC.grantInstall(),
  testSound:k=>OC.testSound(k),
  checkUpdates:()=>OC.checkUpdates(),
  applyAppUpdate:()=>OC.applyAppUpdate()
};
// ---- events from Kotlin. Must exist before any native callback fires.
O.on=(n,f)=>{const h=O._evh=O._evh||{};(h[n]=h[n]||[]).push(f)};
window.OCEvent=(n,d)=>{
  try{
    d=d||{};
    if(n==='chat'){O.onChat&&O.onChat(d);return}
    if(n==='models'){
      if(d.list)O.S.catalog=JSON.parse(JSON.stringify(d.list));
      if(d.error)O.toast('Каталог моделей: '+d.error,3000);
      O.bus.emit('models');return}
    O.on&&O._evh&&(O._evh[n]||[]).forEach(f=>{try{f(d)}catch(e){console.error(n,e)}});
  }catch(e){console.error('OCEvent',n,e)}
};
O.openUrl=u=>{if(/^https?:\/\//i.test(u)&&!/appassets\.androidplatform\.net/i.test(u))O.native.open(u)};
document.addEventListener('click',e=>{const a=e.target.closest&&e.target.closest('a[href]');if(!a)return;
  const u=a.getAttribute('href');e.preventDefault();
  if(/^https?:\/\//i.test(u))O.openUrl(u)});
document.addEventListener('click',e=>{const b=e.target.closest&&e.target.closest('[data-copy]');if(!b)return;const pre=b.closest('.codeblock');if(pre)O.native.copy(pre.querySelector('pre').innerText)});
})();
