(function(){
const O=window.OCM,h=O.h;let el=null,entered=false,permsRender=null,waitT=null;
const screen=(...k)=>{if(!el){el=h('div',{id:'boot'});document.body.append(el)}el.textContent='';el.append(h('div',{class:'center'},...k.flat()));return el};
O.bootHide=()=>{if(el){el.remove();el=null}};
const logo=()=>[O.logo(44),h('b',{style:{fontSize:'22px'}},'OpenCode')];
function login(msg){const st=O.native.state();let b;
  screen(logo(),h('p',{class:'mut'},msg||'Sign in with Google to open your workspace.'),
    st.googleConfigured?null:h('div',{class:'note'},'Google client ID is not set in this build, so a local profile is used.'),
    b=h('button',{class:'btn pri wide',onclick:()=>{b.disabled=true;OC.signIn()}},O.icon('user'),'Sign in with Google'),
    st.user?h('button',{class:'btn wide',onclick:next},'Continue offline'):null,h('span',{class:'mut s'},'v'+st.version))}
function perms(){const render=()=>{const rows=O.permRows();const box=el&&el.querySelector('#pl');if(box){box.textContent='';box.append(rows)}};permsRender=render;
  screen(h('div',{style:{textAlign:'left',width:'100%'}},h('b',{style:{fontSize:'20px'}},'Permissions'),h('p',{class:'mut'},'OpenCode needs these to work like a desktop terminal agent.'),h('div',{id:'pl'},O.permRows()),
    h('button',{class:'btn pri wide',style:{marginTop:'14px'},onclick:()=>{O.store.set('permsSeen',true);permsRender=null;next()}},'Continue')))}
let inst=null;
function install(){const bar=h('i'),msg=h('p',{class:'mut'},'Unpacking the Linux environment. This happens once.'),err=h('p',{class:'err'});
  inst={bar,msg,err};screen(logo(),msg,h('div',{class:'bar',style:{width:'100%'}},bar),err);OC.install()}
O.on('install',d=>{if(!inst||!el)return;
  if(d.error){inst.msg.textContent='Install failed';inst.err.textContent=d.error;if(!el.querySelector('.retry'))el.firstChild.append(h('button',{class:'btn retry',onclick:install},O.icon('refresh'),'Retry'))}
  else if(d.done){inst=null;start()}else inst.bar.style.width=d.pct+'%'});
function start(){const msg=h('p',{class:'mut'},'Starting OpenCode'),err=h('p',{class:'err'}),extra=h('div');
  screen(logo(),h('div',{style:{display:'flex',gap:'8px',alignItems:'center'}},h('span',{class:'spin'}),msg),err,extra);
  OC.start();clearTimeout(waitT);waitT=setTimeout(()=>{if(el&&!entered)extra.append(h('button',{class:'btn wide',onclick:()=>{O.bootHide();entered=true;O.enter({offline:true})}},O.icon('doc'),'Read cached chats while it starts'))},3000)}
function next(){const st=O.native.state(),p=st.perms||{};
  if(!O.store.get('permsSeen')&&!(p.notifications&&p.files&&p.battery&&p.install))return perms();
  if(!st.installed)return install();start()}
O.on('signin',d=>{if(!el)return;d.ok?next():login(d.error||'Sign-in failed')});
O.on('perms',()=>{if(permsRender)permsRender()});
O.on('boot',d=>{if(d.error&&el){const e=el.querySelector('.err');if(e)e.textContent=d.error;if(!el.querySelector('.retry'))el.firstChild.append(h('button',{class:'btn retry',onclick:start},O.icon('refresh'),'Retry'))}});
O.on('ready',()=>{clearTimeout(waitT);O.bootHide();if(!entered){entered=true;O.enter()}else O.reconnect()});
O.bootRun=()=>{const st=O.native.state();
  if(st.ready&&st.user&&st.installed){entered=true;O.enter();return}
  login();OC.signIn()};
})();
