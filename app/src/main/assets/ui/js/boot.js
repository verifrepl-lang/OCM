(function(){
const O=window.OCM,h=O.h;let el=null,entered=false,permsRender=null,waitT=null;
const screen=(...k)=>{if(!el){el=h('div',{id:'boot'});document.body.append(el)}el.textContent='';el.append(h('div',{class:'center'},...k.flat()));return el};
O.bootHide=()=>{if(el){el.remove();el=null}};
const logo=()=>[O.logo(44),h('b',{style:{fontSize:'22px'}},'OpenCode')];
function login(msg){const st=O.native.state();
  const go=p=>{OC.guestProfile();O.store.set('wantLogin',p);next()};
  const goGuest=()=>{OC.guestProfile();O.store.set('wantLogin','');next()};
  screen(logo(),h('b',{style:{fontSize:'20px'}},'Вход в opencode'),
    h('p',{class:'mut'},msg||'Войдите через Google или GitHub — модели и квота привяжутся к вашему аккаунту. Чаты и файлы хранятся только на этом телефоне.'),
    h('button',{class:'btn pri wide',onclick:()=>go('google')},O.icon('user'),'Войти через Google'),
    h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:()=>go('github')},O.icon('github'),'Войти через GitHub'),
    h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:goGuest},'Продолжить без входа'),
    st.user?h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:next},'Продолжить как '+(st.name||st.user)):null,h('span',{class:'mut s'},'v'+st.version))}
function perms(){const render=()=>{const rows=O.permRows();const box=el&&el.querySelector('#pl');if(box){box.textContent='';box.append(rows)}};permsRender=render;
  screen(h('div',{style:{textAlign:'left',width:'100%'}},h('b',{style:{fontSize:'20px'}},'Разрешения'),h('p',{class:'mut'},'OpenCode нужны эти разрешения, чтобы работать как терминальный агент на компьютере.'),h('div',{id:'pl'},O.permRows()),
    h('button',{class:'btn pri wide',style:{marginTop:'14px'},onclick:()=>{O.store.set('permsSeen',true);permsRender=null;next()}},'Продолжить')))}
let inst=null;
function install(){const bar=h('i'),msg=h('p',{class:'mut'},'Распаковываем Linux-окружение. Это происходит один раз.'),err=h('p',{class:'err'});
  inst={bar,msg,err};screen(logo(),msg,h('div',{class:'bar',style:{width:'100%'}},bar),err);OC.install()}
O.on('install',d=>{if(!inst||!el)return;
  if(d.error){inst.msg.textContent='Установка не удалась';inst.err.textContent=d.error;
    if(!el.querySelector('.retry'))el.firstChild.append(h('button',{class:'btn retry',onclick:install},O.icon('refresh'),'Повторить'));
    if(!el.querySelector('.reset'))el.firstChild.append(h('button',{class:'btn reset',style:{marginTop:'8px'},onclick:()=>OC.resetInstall()},O.icon('trash'),'Сбросить и установить заново'))}
  else if(d.done){inst=null;start()}else inst.bar.style.width=d.pct+'%'});
function start(){const msg=h('p',{class:'mut'},'Запуск OpenCode'),err=h('p',{class:'err'}),extra=h('div');
  screen(logo(),h('div',{style:{display:'flex',gap:'8px',alignItems:'center'}},h('span',{class:'spin'}),msg),err,extra);
  OC.start();clearTimeout(waitT);waitT=setTimeout(()=>{if(el&&!entered)extra.append(h('button',{class:'btn wide',onclick:()=>{O.bootHide();entered=true;O.enter({offline:true})}},O.icon('doc'),'Читать кэшированные чаты, пока идёт запуск'))},3000)}
function next(){const st=O.native.state(),p=st.perms||{};
  if(!O.store.get('permsSeen')&&!(p.notifications&&p.files&&p.battery&&p.install))return perms();
  if(!st.installed)return install();start()}
O.on('signin',d=>{if(!el)return;d.ok?next():login(d.error||'Не удалось войти')});
O.on('perms',()=>{if(permsRender)permsRender()});
O.on('boot',d=>{if(d.error&&el){const e=el.querySelector('.err');if(e)e.textContent=d.error;if(!el.querySelector('.retry'))el.firstChild.append(h('button',{class:'btn retry',onclick:start},O.icon('refresh'),'Повторить'))}});
const afterReady=()=>{const w=O.store.get('wantLogin','');if(!w)return;O.store.set('wantLogin','');setTimeout(()=>{if(!el)O.openAccount(true)},600)};
O.on('ready',()=>{clearTimeout(waitT);O.bootHide();if(!entered){entered=true;O.enter();afterReady()}else O.reconnect()});
O.bootRun=()=>{const st=O.native.state();
  if(st.ready&&st.user&&st.installed){entered=true;O.enter();afterReady();return}
  if(st.user)return next();
  login()};
})();
