(function(){
const O=window.OCM,h=O.h;let el=null,entered=false,permsRender=null,waitT=null;
const screen=(...k)=>{if(!el){el=h('div',{id:'boot'});document.body.append(el)}el.textContent='';el.append(h('div',{class:'center'},...k.flat()));return el};
O.bootHide=()=>{if(el){el.remove();el=null}};
const logo=()=>[O.logo(44),h('b',{style:{fontSize:'22px'}},'OpenCode')];
function login(msg){const st=O.native.state();let b,gb,em;
  const goEmail=()=>{const v=em.value.trim();if(!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v)){O.toast('Введите корректный email');return}gb.disabled=true;OC.signInEmail(v)};
  const emailBox=h('div',{style:{width:'100%',display:'flex',gap:'6px'}},
    em=h('input',{type:'email',placeholder:'ваша@почта.ru',autocapitalize:'off',autocorrect:'off',style:{flex:1},onkeydown:e=>{if(e.key==='Enter')goEmail()}}),
    gb=h('button',{class:'btn pri',onclick:goEmail},'Создать'));
  screen(logo(),h('p',{class:'mut'},msg||'Укажите почту — она создаст ваш личный профиль на этом телефоне.'),
    st.googleConfigured?[
      h('div',{class:'note'},'У каждого аккаунта Google — свой отдельный профиль: чаты, файлы и ключи не пересекаются.'),
      b=h('button',{class:'btn pri wide',onclick:()=>{b.disabled=true;OC.signIn()}},O.icon('user'),'Войти через Google')]
    :[
      h('div',{class:'note'},'Почта нужна только для личного профиля на этом телефоне (чаты, файлы, ключи). Пароль не нужен, данные хранятся локально. Это не вход в opencode — после запуска войдите через Google/GitHub: Настройки → Аккаунт opencode.'),
      emailBox,
      h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:()=>{gb.disabled=true;OC.signIn()}},'Продолжить без почты (гость)')],
    st.user?h('button',{class:'btn wide',onclick:next},'Продолжить как '+(st.name||st.user)):null,h('span',{class:'mut s'},'v'+st.version))}
function perms(){const render=()=>{const rows=O.permRows();const box=el&&el.querySelector('#pl');if(box){box.textContent='';box.append(rows)}};permsRender=render;
  screen(h('div',{style:{textAlign:'left',width:'100%'}},h('b',{style:{fontSize:'20px'}},'Разрешения'),h('p',{class:'mut'},'OpenCode нужны эти разрешения, чтобы работать как терминальный агент на компьютере.'),h('div',{id:'pl'},O.permRows()),
    h('button',{class:'btn pri wide',style:{marginTop:'14px'},onclick:()=>{O.store.set('permsSeen',true);permsRender=null;next()}},'Продолжить')))}
let inst=null;
function install(){const bar=h('i'),msg=h('p',{class:'mut'},'Распаковываем Linux-окружение. Это происходит один раз.'),err=h('p',{class:'err'});
  inst={bar,msg,err};screen(logo(),msg,h('div',{class:'bar',style:{width:'100%'}},bar),err);OC.install()}
O.on('install',d=>{if(!inst||!el)return;
  if(d.error){inst.msg.textContent='Установка не удалась';inst.err.textContent=d.error;if(!el.querySelector('.retry'))el.firstChild.append(h('button',{class:'btn retry',onclick:install},O.icon('refresh'),'Повторить'))}
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
O.on('ready',()=>{clearTimeout(waitT);O.bootHide();if(!entered){entered=true;O.enter()}else O.reconnect()});
O.bootRun=()=>{const st=O.native.state();
  if(st.ready&&st.user&&st.installed){entered=true;O.enter();return}
  if(st.user)return next();
  login()};
})();
