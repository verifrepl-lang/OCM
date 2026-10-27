(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;
const search=(ph,fn)=>h('input',{placeholder:ph,oninput:e=>fn(e.target.value.toLowerCase()),style:{marginBottom:'8px'}});

// ---- sessions drawer ----
O.openSessions=()=>{
  const list=h('div'),q=search('Search sessions',()=>draw());
  const s=O.sheet('Sessions',h('div',null,h('button',{class:'btn pri wide',onclick:async()=>{s.close();try{await O.newSession()}catch(e){O.toast(e.message)}}},O.icon('plus'),'New session'),h('div',{style:{height:'10px'}}),q,list),{drawer:true});
  function draw(){list.textContent='';const f=q.value.toLowerCase();
    const items=S.sessions.filter(x=>O.sessionTitle(x).toLowerCase().includes(f));
    if(!items.length)list.append(h('div',{class:'mut',style:{padding:'20px',textAlign:'center'}},'No sessions yet'));
    items.forEach(x=>{const st=S.status[x.id],b=st&&st.type!=='idle',sm=x.summary;
      list.append(h('div',{class:'li'+(x.id===S.sid?' on':'')},
        h('button',{class:'t',style:{textAlign:'left',flex:1,minWidth:0},onclick:()=>{s.close();O.openSession(x.id)}},
          h('b',{style:{display:'block',overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'}},O.sessionTitle(x)),
          h('span',{class:'mut s'},O.ago(x.time.updated)+(sm&&(sm.additions||sm.deletions)?'  +'+sm.additions+' -'+sm.deletions:''))),
        b?h('span',{class:'spin'}):null,h('button',{class:'ibtn s',onclick:()=>sessActions(x)},O.icon('dots'))))})}
  draw();bus.on('sessions',()=>{if(s.root.isConnected)draw()})};
function sessActions(x){O.actions(O.sessionTitle(x),[
  {label:'Rename',icon:'edit',fn:async()=>{const v=await O.ask('Rename session',null,O.sessionTitle(x));if(v)try{await api('PATCH','/session/'+x.id,{title:v})}catch(e){O.toast(e.message)}}},
  {label:x.share?'Stop sharing':'Share',icon:'share',fn:async()=>{try{if(x.share){await api('DELETE','/session/'+x.id+'/share')}else{const r=await api('POST','/session/'+x.id+'/share',{});if(r.share){O.native.copy(r.share.url);O.native.share(r.share.url)}}}catch(e){O.toast(e.message)}}},
  {label:'Fork',icon:'branch',fn:async()=>{try{const r=await api('POST','/session/'+x.id+'/fork',{});await O.loadSessions();O.openSession(r.id)}catch(e){O.toast(e.message)}}},
  {label:'Delete',icon:'trash',danger:true,fn:async()=>{if(await O.confirm('Delete session?',O.sessionTitle(x),'Delete',true))try{await api('DELETE','/session/'+x.id);S.sessions=S.sessions.filter(y=>y.id!==x.id);if(S.sid===x.id)O.openSession(S.sessions[0]&&S.sessions[0].id||null);bus.emit('sessions')}catch(e){O.toast(e.message)}}}])}

// ---- projects + folder browser ----
O.openProjects=()=>{
  const recent=O.store.get('recent',[]),seen=new Set(),rows=[];
  const add=(p,sub)=>{if(!p||seen.has(p))return;seen.add(p);rows.push(O.listRow(O.fileIcon(O.basename(p)||'/',true),O.basename(p)||'/',p,p===S.dir?O.icon('check','ok'):null,()=>{s.close();O.setDir(p)}))};
  add(S.dir);recent.forEach(p=>add(p));(S.projects||[]).forEach(x=>add(x.worktree));add('/root');
  const s=O.sheet('Projects',h('div',null,h('button',{class:'btn pri wide',onclick:()=>{s.close();O.browseDir(S.dir,p=>O.setDir(p))}},O.icon('folder'),'Open folder'),h('div',{style:{height:'8px'}}),rows))};
O.browseDir=(start,cb)=>{let path=start||'/root';
  const crumbs=h('div',{class:'mono s mut',style:{wordBreak:'break-all',margin:'6px 2px'}}),list=h('div');
  const short=(l,p)=>h('button',{class:'chip',onclick:()=>{path=p;load()}},O.icon('folder'),h('span',null,l));
  const s=O.sheet('Choose folder',h('div',null,h('div',{style:{display:'flex',gap:'6px',overflowX:'auto'}},short('Home','/root'),short('Storage','/sdcard'),short('Downloads','/sdcard/Download'),short('Root','/')),crumbs,list),{full:true,
    actions:h('button',{class:'btn pri sm',onclick:()=>{s.close();cb(path)}},'Select')});
  function load(){const r=O.native.ls(path);crumbs.textContent=path;list.textContent='';
    if(path!=='/')list.append(O.listRow('chevL','..',null,null,()=>{path=O.dirname(path);load()}));
    if(r.err||r.ok===false){list.append(h('div',{class:'err',style:{padding:'12px'}},r.err||'Cannot read folder'));return}
    (r.items||[]).filter(x=>x.dir).forEach(x=>list.append(O.listRow(O.fileIcon(x.name,true),x.name,null,null,()=>{path=(path==='/'?'':path)+'/'+x.name;load()})));
    list.append(h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:async()=>{const n=await O.ask('New folder','Name');if(!n)return;const rr=O.native.fs('mkdir',(path==='/'?'':path)+'/'+n);rr.ok?load():O.toast(rr.err||'Failed')}},O.icon('plus'),'New folder'))}
  load()};

// ---- models / agents ----
O.openModels=()=>{
  const list=h('div'),q=search('Search models',()=>draw());
  const s=O.sheet('Models',h('div',null,h('button',{class:'btn wide',onclick:()=>{s.close();O.openConnect()}},O.icon('key'),'Connect provider'),h('div',{style:{height:'8px'}}),q,list));
  function draw(){list.textContent='';const f=q.value.toLowerCase();const all=O.allModels();
    if(!all.length)list.append(h('div',{class:'mut',style:{padding:'16px'}},'No models yet. Connect a provider, or use OpenCode Zen free models.'));
    const rec=O.store.get('recentModels',[]).map(k=>all.find(x=>x.pid+'/'+x.mid===k)).filter(Boolean);
    const groups=[];if(rec.length&&!f)groups.push(['Recent',rec]);
    const by={};all.forEach(x=>{(by[x.pname||x.pid]=by[x.pname||x.pid]||[]).push(x)});Object.keys(by).forEach(k=>groups.push([k,by[k]]));
    groups.forEach(([g,ms])=>{const items=ms.filter(x=>!f||(x.name+' '+x.mid+' '+g).toLowerCase().includes(f));if(!items.length)return;
      list.append(h('div',{class:'grp'},g));
      items.forEach(x=>{const on=S.model&&S.model.providerID===x.pid&&S.model.modelID===x.mid,c=x.m.cost,free=c&&c.input===0&&c.output===0,ctx=x.m.limit&&x.m.limit.context;
        list.append(O.listRow('cpu',x.name,x.mid+(ctx?'  '+O.fmtK(ctx)+' ctx':'')+(free?'  free':''),on?O.icon('check','ok'):null,()=>{
          const r=O.store.get('recentModels',[]).filter(k=>k!==x.pid+'/'+x.mid);r.unshift(x.pid+'/'+x.mid);O.store.set('recentModels',r.slice(0,6));O.setModel(x.pid,x.mid);s.close()}))})})}
  draw()};
O.openAgents=()=>{const ags=O.primaryAgents();
  const s=O.sheet('Agents',h('div',null,ags.map(a=>O.listRow('bot',a.name,a.description||'',a.name===S.agent?O.icon('check','ok'):null,()=>{O.setAgent(a.name);s.close()})),
    h('div',{class:'note',style:{marginTop:'12px'}},'Add your own agents in Settings > Agents and rules.')))};

// ---- connect providers (/connect) ----
const POP=['opencode','anthropic','openai','github-copilot','google','openrouter','xai','deepseek','groq','mistral','together','fireworks','cerebras','nvidia','huggingface'];
O.openConnect=async()=>{
  const body=h('div',null,h('div',{class:'mut'},'Loading...'));const s=O.sheet('Connect a provider',body,{full:true});
  let all=[],conn=new Set(),auth={};
  try{const r=await api('GET','/provider');all=r.all||[];conn=new Set(r.connected||[]);try{auth=await api('GET','/provider/auth')}catch{}}catch(e){body.textContent=e.message;return}
  const name=p=>p.id==='opencode'?'OpenCode Zen':(p.name||p.id);
  const q=search('Search providers',()=>draw()),list=h('div');
  const draw=()=>{list.textContent='';const f=q.value.toLowerCase();
    const sorted=[...all].sort((a,b)=>{const ca=conn.has(a.id)?0:1,cb=conn.has(b.id)?0:1;if(ca!==cb)return ca-cb;const pa=POP.indexOf(a.id),pb=POP.indexOf(b.id);return(pa<0?99:pa)-(pb<0?99:pb)||name(a).localeCompare(name(b))});
    sorted.filter(p=>!f||(name(p)+p.id).toLowerCase().includes(f)).forEach(p=>list.append(O.listRow('cloud',name(p),p.id==='opencode'?'Free models, no key needed - add a Zen key for paid ones':p.id,conn.has(p.id)?O.icon('check','ok'):null,()=>pick(p))))};
  body.textContent='';body.append(q,list);draw();
  const after=async()=>{try{await api('POST','/instance/dispose',null)}catch{}await O.loadMeta();O.toast('Connected');s.close()};
  const pick=p=>{const ms=(auth[p.id]||[]).map((m,i)=>({m,i}));if(!ms.length)ms.push({m:{type:'api',label:'API key'},i:0});
    const go=x=>x.m.type==='oauth'?oauth(p,x):keyForm(p);
    if(ms.length===1&&!conn.has(p.id))return go(ms[0]);
    O.actions(name(p),[...ms.map(x=>({label:x.m.label||(x.m.type==='oauth'?'Sign in':'API key'),icon:x.m.type==='oauth'?'user':'key',fn:()=>go(x)})),
      conn.has(p.id)?{label:'Disconnect',icon:'trash',danger:true,fn:()=>disconnect(p)}:null])};
  const keyForm=p=>{const inp=h('input',{type:'password',placeholder:'Paste API key',autocapitalize:'off',autocorrect:'off'});
    const sh=O.sheet(name(p),h('div',null,h('div',{class:'field'},h('label',null,'API key'),inp),
      p.id==='opencode'?h('div',{class:'note',style:{margin:'8px 0'}},'Get a Zen key at opencode.ai/zen. Free models work without one.'):null,
      h('button',{class:'btn pri wide',onclick:async()=>{if(!inp.value.trim())return;try{await api('PUT','/auth/'+p.id,{type:'api',key:inp.value.trim()});sh.close();after()}catch(e){O.toast(e.message)}}},'Save')))};
  const oauth=async(p,x)=>{const view=h('div',null,h('div',{class:'mut'},'Preparing...'));const sh=O.sheet(name(p),view);
    try{const r=await api('POST','/provider/'+p.id+'/oauth/authorize',{method:x.i});view.textContent='';
      view.append(h('div',null,r.instructions||'Open the link and approve access.'),h('div',{style:{display:'flex',gap:'8px',margin:'12px 0'}},
        h('button',{class:'btn pri grow',onclick:()=>O.openUrl(r.url)},O.icon('ext'),'Open link'),h('button',{class:'btn',onclick:()=>O.native.copy(r.url)},O.icon('copy'))));
      if(r.method==='code'){const inp=h('input',{placeholder:'Paste the code'});view.append(inp,h('button',{class:'btn pri wide',style:{marginTop:'8px'},onclick:async()=>{try{await api('POST','/provider/'+p.id+'/oauth/callback',{method:x.i,code:inp.value.trim()},{timeout:120000});sh.close();after()}catch(e){O.toast(e.message)}}},'Submit'))}
      else{view.append(h('div',{style:{display:'flex',gap:'8px',alignItems:'center'}},h('span',{class:'spin'}),h('span',{class:'mut'},'Waiting for you to approve...')));
        api('POST','/provider/'+p.id+'/oauth/callback',{method:x.i},{timeout:900000}).then(()=>{if(sh.root.isConnected){sh.close();after()}}).catch(e=>{if(sh.root.isConnected)O.toast(e.message)})}
    }catch(e){view.textContent=e.message}};
  const disconnect=async p=>{const r=O.native.readText('/root/.local/share/opencode/auth.json');
    try{const o=JSON.parse(r.text||'{}');delete o[p.id];const w=O.native.writeText('/root/.local/share/opencode/auth.json',JSON.stringify(o,null,2));if(!w.ok)throw new Error(w.err||'write failed');
      try{await api('POST','/instance/dispose',null)}catch{}await O.loadMeta();conn.delete(p.id);draw();O.toast('Disconnected')}catch(e){O.toast(e.message)}}};

// ---- status: MCP / LSP / plugins ----
O.openStatus=async()=>{
  const body=h('div'),tabs=h('div',{class:'seg',style:{marginBottom:'10px'}});const s=O.sheet('Status',h('div',null,tabs,body));
  let mcp={},lsp=[];try{mcp=await api('GET','/mcp')}catch{}try{lsp=await api('GET','/lsp')}catch{}
  const plugins=[].concat(S.cfg&&S.cfg.plugin||[]).map(x=>Array.isArray(x)?x[0]:x);
  const defs=[['MCP',()=>{body.textContent='';const ks=Object.keys(mcp);if(!ks.length)body.append(h('div',{class:'mut'},'No MCP servers configured.'));
      ks.forEach(k=>{const m=mcp[k],st=m.status;const dot=h('span',{class:'dot '+(st==='connected'?'ok':st==='failed'?'err':st==='disabled'?'':'warn')});
        body.append(O.listRow(O.icon('server'),k,st+(m.error?': '+m.error:''),dot,async()=>{try{if(st==='connected')await api('POST','/mcp/'+encodeURIComponent(k)+'/disconnect',{});else if(st==='needs_auth'){const r=await api('POST','/mcp/'+encodeURIComponent(k)+'/auth',{});if(r.authorizationUrl)O.openUrl(r.authorizationUrl)}else await api('POST','/mcp/'+encodeURIComponent(k)+'/connect',{});mcp=await api('GET','/mcp');defs[0][1]()}catch(e){O.toast(e.message)}}))});
      body.append(h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:()=>{s.close();O.openMcp()}},'Manage MCP servers'))}],
    ['LSP',()=>{body.textContent='';if(!lsp.length)body.append(h('div',{class:'mut'},'No language servers running yet. They start when files are opened.'));
      lsp.forEach(l=>body.append(O.listRow('code',l.name||l.id,l.root||'',h('span',{class:'dot '+(l.status==='connected'?'ok':'err')}))))}],
    [plugins.length+' Plugins',()=>{body.textContent='';if(!plugins.length)body.append(h('div',{class:'mut'},'No plugins installed.'));
      plugins.forEach(p=>body.append(O.listRow('plug',p,'',h('span',{class:'dot ok'}))));
      body.append(h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:()=>{s.close();O.openPlugins()}},'Manage plugins'))}]];
  defs.forEach((d,i)=>tabs.append(h('button',{class:i===0?'on':'',onclick:e=>{tabs.querySelectorAll('button').forEach(b=>b.classList.toggle('on',b===e.currentTarget));d[1]()}},d[0])));defs[0][1]()};

// ---- themes / help ----
O.openThemes=()=>{const th=[['opencode','OpenCode','#131010','#fab283'],['light','Light','#fafaf8','#c2641c'],['tokyonight','Tokyo Night','#1a1b26','#7aa2f7'],['gruvbox','Gruvbox','#282828','#fabd2f']];
  const s=O.sheet('Appearance',h('div',null,th.map(t=>O.listRow(h('span',{style:{width:'22px',height:'22px',borderRadius:'6px',background:t[2],border:'3px solid '+t[3],flex:'none'}}),t[1],'',S.opts.theme===t[0]?O.icon('check','ok'):null,()=>{S.opts.theme=t[0];O.saveOpts();s.close()})),
    h('div',{class:'grp'},'Text size'),h('div',{class:'seg'},[13,14,15,16,18].map(n=>h('button',{class:S.opts.fs===n?'on':'',onclick:()=>{S.opts.fs=n;O.saveOpts();s.close()}},n)))))};
O.openHelp=()=>O.sheet('Help',h('div',null,h('div',{class:'grp'},'Commands'),O.builtins.map(b=>O.listRow(b.i,'/'+b.n,b.d,null,()=>{O.back();b.run()})),
  h('div',{class:'grp'},'Prompt shortcuts'),h('div',{class:'note'},'@file mentions a file or folder.  !command runs a shell command in the session.  Long-press a file in the tree for actions.')));
})();
