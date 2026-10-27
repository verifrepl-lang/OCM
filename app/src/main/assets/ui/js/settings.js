(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;
const CFG='/root/.config/opencode';
const cfg={read(){const t=O.native.cfgRead();if(!t.trim())return{obj:{},raw:''};try{return{obj:O.parseJsonc(t),raw:t}}catch(e){return{obj:null,raw:t,err:e.message}}},
  write(o){return O.native.cfgWrite(typeof o==='string'?o:JSON.stringify(o,null,2))}};
const restartBar=()=>h('button',{class:'btn wide',style:{marginTop:'12px'},onclick:()=>{OC.restartServer();O.toast('Restarting server...');O.back()}},O.icon('refresh'),'Restart server to apply');
const sub=(t,b,o)=>O.sheet(t,b,Object.assign({full:true},o));

// ---- main ----
O.openSettings=()=>{
  const st=O.native.state(),fl=st.flags||{};
  const body=h('div');const s=sub('Settings',body);
  body.append(h('div',{class:'card',style:{display:'flex',alignItems:'center',gap:'10px'}},O.icon('user'),h('div',{class:'grow'},h('b',null,st.name||'Local'),h('div',{class:'mut s'},st.user||'')),
    h('button',{class:'btn sm',onclick:async()=>{if(await O.confirm('Sign out?','Chats stay on this phone.','Sign out'))OC.signOut()}},O.icon('out'),'Sign out')));
  const g=t=>body.append(h('div',{class:'grp'},t)),r=(i,t,sb,fn)=>body.append(O.listRow(i,t,sb,O.icon('chevR'),fn));
  g('Models');r('key','Connect a provider','OpenCode Zen, OpenAI, Anthropic, GitHub Copilot, Google and more',()=>O.openConnect());
  r('cpu','Model',S.model?S.model.providerID+'/'+S.model.modelID:'none selected',()=>O.openModels());
  g('Appearance');r('palette','Theme and text size',S.opts.theme+', '+S.opts.fs+'px',()=>O.openThemes());
  body.append(O.toggleRow('bulb','Show reasoning','Thinking blocks in answers',S.opts.thinking,v=>{S.opts.thinking=v;O.saveOpts();O.chat.renderAll()}));
  body.append(O.toggleRow('code','Expandable tool details','Tap a tool card to see commands, output and diffs',S.opts.details,v=>{S.opts.details=v;O.saveOpts();O.chat.renderAll()}));
  g('Sounds and notifications');
  [['snd_done','Task finished','Agent completed its work','done'],['snd_action','Action required','Permission or question is waiting','action'],['snd_quota','Quota reached','Model limit or rate limit hit','quota']].forEach(([k,t,d,kind])=>
    body.append(h('div',{style:{display:'flex',alignItems:'center'}},h('div',{class:'grow'},O.toggleRow('bell',t,d,fl[k]!==false,v=>OC.setFlag(k,v))),h('button',{class:'ibtn',onclick:()=>OC.testSound(kind)},O.icon('play')))));
  g('Agents');r('doc','Agents and rules','Upload AGENTS.md, agents and commands',()=>openAgents());
  r('server','MCP servers','Add, edit, connect',()=>O.openMcp());r('plug','Plugins','npm packages and local files',()=>O.openPlugins());
  r('code','Config file','Edit opencode.json directly',()=>openConfig());
  g('App');r('down','Updates','App and OpenCode core (1.x)',()=>openUpdates());r('shield','Permissions','Files, notifications, background, install',()=>openPerms());
  r('bat','Compatibility and logs','Safe mode, restart server',()=>openCompat());r('info','About','',()=>openAbout());
  r('tg','Community','t.me/OpenCodeMob',()=>O.openUrl('https://t.me/OpenCodeMob'))};

// ---- agents / rules upload ----
function openAgents(){const list=h('div');
  const up=(t,sbt,kind,dir)=>O.listRow('up',t,sbt,null,()=>OC.pickFile(kind,dir||''));
  const s=sub('Agents and rules',h('div',null,
    up('Global rules (AGENTS.md)','Pick a file - applies to every project','rules'),
    O.listRow('edit','Write global rules','Edit AGENTS.md here',null,()=>editFile(CFG+'/AGENTS.md','Global AGENTS.md')),
    up('Project rules (AGENTS.md)','Saved into the current folder','project',S.dir),
    up('Agent file (.md)','Added to your agents list','agent'),up('Command file (.md)','Adds a /command','command'),list));
  const draw=()=>{list.textContent='';[['agents','Agents'],['commands','Commands']].forEach(([d,t])=>{const r=O.native.ls(CFG+'/'+d);if(r.items&&r.items.length){list.append(h('div',{class:'grp'},t));
    r.items.filter(x=>!x.dir).forEach(x=>list.append(O.listRow('doc',x.name,'',h('button',{class:'ibtn s',onclick:async e=>{e.stopPropagation();if(await O.confirm('Delete '+x.name+'?','','Delete',true)){O.native.fs('delete',CFG+'/'+d+'/'+x.name);draw();O.loadMeta()}}},O.icon('trash')),()=>editFile(CFG+'/'+d+'/'+x.name,x.name))))}})};
  draw();O.on('picked',()=>{if(s.root.isConnected)draw()})}
function editFile(path,title){const r=O.native.readText(path);const ta=h('textarea',{class:'mono',rows:18});ta.value=r.ok?r.text:'';
  const s=O.sheet(title,h('div',null,ta,h('button',{class:'btn pri wide',style:{marginTop:'8px'},onclick:()=>{const w=O.native.fs('mkdir',O.dirname(path));const x=O.native.writeText(path,ta.value);if(x.ok){O.toast('Saved',1200);s.close();O.loadMeta()}else O.toast(x.err||'Failed')}},'Save')),{full:true})}

// ---- MCP ----
O.openMcp=async()=>{const body=h('div');const s=sub('MCP servers',body,{actions:h('button',{class:'ibtn',onclick:()=>edit(null)},O.icon('plus'))});
  const draw=async()=>{body.textContent='';const c=cfg.read();if(!c.obj){body.append(h('div',{class:'errbox'},'opencode.json has errors: '+c.err));return}
    let live={};try{live=await api('GET','/mcp')}catch{}const m=c.obj.mcp||{};const ks=Object.keys(m);
    if(!ks.length)body.append(h('div',{class:'mut',style:{padding:'12px'}},'No MCP servers yet. Tap + to add one.'));
    ks.forEach(k=>{const e=m[k],l=live[k],stt=l?l.status:(e.enabled===false?'disabled':'unknown');
      body.append(O.listRow('server',k,(e.type==='remote'?e.url:(e.command||[]).join(' '))+'  ('+stt+')',h('span',{class:'dot '+(stt==='connected'?'ok':stt==='failed'?'err':stt==='disabled'?'':'warn')}),
        ()=>O.actions(k,[{label:'Edit',icon:'edit',fn:()=>edit(k)},
          {label:e.enabled===false?'Enable':'Disable',icon:'play',fn:()=>{const cc=cfg.read();cc.obj.mcp[k].enabled=e.enabled===false;cfg.write(cc.obj);api('POST','/mcp/'+encodeURIComponent(k)+(e.enabled===false?'/connect':'/disconnect'),{}).catch(()=>{});draw()}},
          {label:'Remove',icon:'trash',danger:true,fn:()=>{const cc=cfg.read();delete cc.obj.mcp[k];cfg.write(cc.obj);api('POST','/mcp/'+encodeURIComponent(k)+'/disconnect',{}).catch(()=>{});draw()}}])))});
    body.append(restartBar())};
  const edit=name=>{const c=cfg.read();if(!c.obj)return O.toast('Fix opencode.json first');const e=(name&&c.obj.mcp&&c.obj.mcp[name])||{type:'local',enabled:true};
    const nm=h('input',{placeholder:'name, e.g. context7'});nm.value=name||'';nm.disabled=!!name;
    let type=e.type||'local';const cmd=h('input',{class:'mono',placeholder:'npx -y @upstash/context7-mcp'});cmd.value=(e.command||[]).join(' ');
    const env=h('textarea',{class:'mono',rows:3,placeholder:'KEY=value (one per line)'});env.value=Object.entries(e.environment||{}).map(([k,v])=>k+'='+v).join('\n');
    const url=h('input',{class:'mono',placeholder:'https://mcp.example.com/mcp'});url.value=e.url||'';
    const hd=h('textarea',{class:'mono',rows:3,placeholder:'Header: value (one per line)'});hd.value=Object.entries(e.headers||{}).map(([k,v])=>k+': '+v).join('\n');
    const lo=h('div',null,h('div',{class:'field'},h('label',null,'Command'),cmd),h('div',{class:'field'},h('label',null,'Environment'),env));
    const re=h('div',null,h('div',{class:'field'},h('label',null,'URL'),url),h('div',{class:'field'},h('label',null,'Headers'),hd));
    const seg=h('div',{class:'seg'},['local','remote'].map(t=>h('button',{class:t===type?'on':'',onclick:ev=>{type=t;seg.querySelectorAll('button').forEach(b=>b.classList.toggle('on',b===ev.currentTarget));show()}},t)));
    const show=()=>{lo.classList.toggle('hide',type!=='local');re.classList.toggle('hide',type!=='remote')};show();
    const sh=O.sheet(name?'Edit '+name:'Add MCP server',h('div',null,h('div',{class:'field'},h('label',null,'Name'),nm),seg,lo,re,
      h('button',{class:'btn pri wide',style:{marginTop:'10px'},onclick:async()=>{const n=nm.value.trim();if(!n)return O.toast('Name is required');
        const o={type,enabled:e.enabled!==false};
        if(type==='local'){o.command=O.splitArgs(cmd.value);if(!o.command.length)return O.toast('Command is required');const ev={};env.value.split('\n').forEach(l=>{const i=l.indexOf('=');if(i>0)ev[l.slice(0,i).trim()]=l.slice(i+1).trim()});if(Object.keys(ev).length)o.environment=ev}
        else{if(!url.value.trim())return O.toast('URL is required');o.url=url.value.trim();const hv={};hd.value.split('\n').forEach(l=>{const i=l.indexOf(':');if(i>0)hv[l.slice(0,i).trim()]=l.slice(i+1).trim()});if(Object.keys(hv).length)o.headers=hv}
        const cc=cfg.read();cc.obj.mcp=cc.obj.mcp||{};cc.obj.mcp[n]=o;const w=cfg.write(cc.obj);if(!w.ok)return O.toast(w.err||'Cannot write config');
        api('POST','/mcp',{name:n,config:o}).catch(()=>{});sh.close();draw()}},'Save')),{full:true})};
  draw()};

// ---- plugins ----
O.openPlugins=()=>{const body=h('div');const s=sub('Plugins',body);
  const draw=()=>{body.textContent='';const c=cfg.read();if(!c.obj){body.append(h('div',{class:'errbox'},'opencode.json has errors: '+c.err));return}
    const pl=c.obj.plugin||[];
    const inp=h('input',{class:'mono',placeholder:'npm package, e.g. opencode-gemini-auth',autocapitalize:'off',autocorrect:'off'});
    body.append(h('div',{class:'grp'},'Installed (npm)'));
    if(!pl.length)body.append(h('div',{class:'mut',style:{padding:'8px'}},'None'));
    pl.forEach((p,i)=>body.append(O.listRow('plug',Array.isArray(p)?p[0]:p,'',h('button',{class:'ibtn s',onclick:e=>{e.stopPropagation();const cc=cfg.read();cc.obj.plugin.splice(i,1);cfg.write(cc.obj);draw()}},O.icon('trash')))));
    body.append(h('div',{style:{display:'flex',gap:'6px',marginTop:'8px'}},inp,h('button',{class:'btn pri',onclick:()=>{const v=inp.value.trim();if(!v)return;const cc=cfg.read();cc.obj.plugin=(cc.obj.plugin||[]).concat([v]);cfg.write(cc.obj);draw()}},'Add')));
    body.append(h('div',{class:'grp'},'Local files'));const r=O.native.ls(CFG+'/plugins');
    (r.items||[]).filter(x=>!x.dir).forEach(x=>body.append(O.listRow('code',x.name,'',h('button',{class:'ibtn s',onclick:e=>{e.stopPropagation();O.native.fs('delete',CFG+'/plugins/'+x.name);draw()}},O.icon('trash')))));
    body.append(h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:()=>OC.pickFile('plugin','')},O.icon('up'),'Upload plugin file (.js / .ts)'),
      h('div',{class:'note',style:{marginTop:'10px'}},'npm plugins are installed automatically on the next server start.'),restartBar())};
  draw();O.on('picked',()=>{if(s.root.isConnected)draw()})};

// ---- raw config ----
function openConfig(){const c=cfg.read();const ta=h('textarea',{class:'mono',rows:20,spellcheck:'false'});ta.value=c.raw||'{\n}\n';
  sub('opencode.json',h('div',null,c.err?h('div',{class:'errbox'},c.err):null,ta,h('button',{class:'btn pri wide',style:{marginTop:'8px'},onclick:()=>{try{O.parseJsonc(ta.value||'{}')}catch(e){return O.toast('Invalid JSON: '+e.message,4000)}
    const w=cfg.write(ta.value);w.ok?O.toast('Saved',1200):O.toast(w.err||'Failed')}},'Save'),restartBar()))}

// ---- updates ----
let updUI=null;
function openUpdates(){const st=O.native.state();const bar=h('i');const info=h('div',{class:'mut s',style:{margin:'8px 0'}},'');
  const app=h('div'),core=h('div');
  const row=(t,sb,btn)=>h('div',{class:'li'},O.icon('down'),h('div',{class:'t'},h('b',null,t),h('span',null,sb)),btn||null);
  const draw=u=>{app.textContent='';core.textContent='';
    app.append(row('OpenCode app '+st.version,u&&u.app?'New version '+u.app.name:'Up to date',u&&u.app?h('button',{class:'btn pri sm',onclick:()=>OC.applyAppUpdate()},'Install'):null));
    core.append(row('OpenCode core '+(st.core||''),u&&u.core?'New release '+u.core:'1.x line',u&&u.core?h('button',{class:'btn pri sm',onclick:()=>OC.applyCoreUpdate()},'Update'):null))};
  draw(null);
  const s=sub('Updates',h('div',null,app,core,h('button',{class:'btn wide',onclick:()=>{info.textContent='Checking...';OC.checkUpdates()}},O.icon('refresh'),'Check for updates'),info,h('div',{class:'bar',style:{marginTop:'8px'}},bar),
    h('div',{class:'note',style:{marginTop:'12px'}},'Core updates only follow the 1.x line and apply without reinstalling the app.')),{onClose:()=>{updUI=null}});
  updUI={draw,bar,info,s}}
O.on('update',d=>{if(!updUI)return;
  if(d.error){updUI.info.textContent=d.error;updUI.bar.style.width='0'}
  else if(d.pct!=null){updUI.bar.style.width=d.pct+'%';updUI.info.textContent=(d.what==='core'?'Downloading core ':'Downloading app ')+d.pct+'%'}
  else if(d.coreDone){updUI.bar.style.width='0';updUI.info.textContent='Core updated to '+d.coreDone+'. Restarting server...'}
  else{updUI.draw(d);updUI.info.textContent=(d.app||d.core)?'Updates available':'Everything is up to date'}});

// ---- permissions ----
const PERMS=[['notifications','bell','Notifications','Sounds and alerts when a task ends or needs you'],['files','folder','All files access','Work in /storage/emulated/0 and Download'],['battery','bat','Run in background','Ignore battery optimisation so the server is not killed'],['install','down','Install updates','Allow OTA updates to install the new APK']];
let permUI=null;
O.permRows=()=>{const p=O.native.state().perms||{};return PERMS.map(([k,i,t,d])=>h('div',{class:'li'},O.icon(i),h('div',{class:'t'},h('b',null,t),h('span',null,d)),p[k]?h('span',{class:'ok s'},'Granted'):h('button',{class:'btn sm',onclick:()=>OC.grant(k)},'Allow')))};
function openPerms(){const box=h('div',null,O.permRows());const s=sub('Permissions',box,{onClose:()=>{permUI=null}});permUI=()=>{box.textContent='';box.append(O.permRows())}}
O.on('perms',()=>{if(permUI)permUI()});

// ---- compatibility / about ----
function openCompat(){const fl=O.native.state().flags||{};
  sub('Compatibility and logs',h('div',null,O.toggleRow('shield','Safe mode (no seccomp)','Slower, but helps if the server fails to start on some phones',fl.noseccomp,v=>OC.setFlag('noseccomp',v)),
    h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:()=>{OC.restartServer();O.toast('Restarting server...')}},O.icon('refresh'),'Restart server'),
    h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:()=>{const t=OC.readLog();sub('Server log',h('pre',{class:'term-pre',style:{maxHeight:'none'}},t||'(empty)'))}},O.icon('terminal'),'View server log')))}
function openAbout(){const st=O.native.state();
  sub('About',h('div',null,h('div',{class:'center',style:{minHeight:0,padding:'16px 0'}},O.logo(40),h('b',{style:{fontSize:'20px'}},'OpenCode'),h('div',{class:'mut'},'Version '+st.version),h('div',{class:'mut s'},'Core '+(st.core||'unknown')+' (1.x)')),
    h('div',{class:'note'},'OpenCode Mobile packages the MIT-licensed OpenCode 1.x CLI in an Android app. It is not built by the OpenCode team and is not affiliated with them.'),
    h('button',{class:'btn wide',style:{marginTop:'12px'},onclick:()=>O.openUrl('https://t.me/OpenCodeMob')},O.icon('tg'),'t.me/OpenCodeMob')))}
O.on('signedout',()=>location.reload());
})();
