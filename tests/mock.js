(function(){
const srv={sessions:[],msgs:{},calls:[]};window.__srv=srv;
const route=(method,path,body)=>{const u=new URL('http://x'+path),p=u.pathname;srv.calls.push({method,p,q:u.search,body:body?JSON.parse(body):null});
 if(p==='/config/providers')return{providers:[{id:'opencode',name:'OpenCode Zen',models:{'big-pickle':{id:'big-pickle',name:'Big Pickle',limit:{context:200000},cost:{input:0,output:0},variants:{low:{},high:{}}},'gpt-x':{id:'gpt-x',name:'GPT X',limit:{context:100000},cost:{input:1,output:2}}}}],default:{opencode:'big-pickle'}};
 if(p==='/provider')return{all:[{id:'opencode',name:'OpenCode'},{id:'anthropic',name:'Anthropic'}],connected:['opencode'],default:{}};
 if(p==='/provider/auth')return{anthropic:[{type:'oauth',label:'Claude Pro/Max'},{type:'api',label:'API key'}]};
 if(p==='/agent')return[{name:'build',mode:'primary',description:'Default agent'},{name:'plan',mode:'primary',description:'Read-only'},{name:'general',mode:'subagent',description:'Search'}];
 if(p==='/command')return[{name:'review',description:'Review changes'}];
 if(p==='/config')return{plugin:['oh-my-openagent@latest','opencode-gemini-auth']};
 if(p==='/project')return[{id:'p1',worktree:'/root/app'}];
 if(p==='/vcs')return{branch:'main'};
 if(p==='/session'&&method==='GET')return srv.sessions;
 if(p==='/session'&&method==='POST'){const s={id:'ses_'+(srv.sessions.length+1),title:'New session - 2026',time:{created:Date.now(),updated:Date.now()},directory:'/root'};srv.sessions.unshift(s);srv.msgs[s.id]=[];return s}
 if(p==='/session/status')return{};
 if(/\/session\/[^/]+\/message$/.test(p))return srv.msgs[p.split('/')[2]]||[];
 if(/\/(todo|diff)$/.test(p))return[];
 if(p==='/permission'||p==='/question')return[];
 if(p==='/file')return[{name:'src',path:'src',absolute:'/root/src',type:'directory',ignored:false},{name:'main.py',path:'main.py',absolute:'/root/main.py',type:'file',ignored:false},{name:'README.md',path:'README.md',absolute:'/root/README.md',type:'file',ignored:false}];
 if(p==='/file/status')return[{path:'main.py',added:2,removed:1,status:'modified'}];
 if(p==='/file/content')return{type:'text',content:'import os\nprint("hi")\n'};
 if(p==='/mcp')return{ctx7:{status:'connected'},broken:{status:'failed',error:'spawn error'}};
 if(p==='/lsp')return[];
 if(p==='/find/file')return['main.py','src/'];
 if(/prompt_async$/.test(p)){setTimeout(()=>window.__onPrompt(JSON.parse(body)),30);return{}}
 if(/permission/.test(p))return true;
 return{}};
let sse=false;
window.__push=ev=>OCEvent('sse',{t:'data',d:JSON.stringify({directory:'/root',payload:ev})});
window.OC={
 state:()=>JSON.stringify({version:'1.0.6',code:1,user:'dev@example.com',name:'Dev',googleConfigured:true,installed:true,core:'v1.18.5',perms:{notifications:true,files:true,install:true,battery:false},flags:{snd_done:true,snd_action:true,snd_quota:false,noseccomp:false},ready:true}),
 signIn(){},signOut(){},install(){},start(){setTimeout(()=>OCEvent('ready',{}),10)},restartServer(){window.__restarted=1},minimize(){},grant(k){window.__granted=k},setFlag(k,v){(window.__flags=window.__flags||{})[k]=v},testSound(){},
 http(id,m,p,b){setTimeout(()=>{try{OCEvent('http',{id,status:200,ct:'application/json',body:JSON.stringify(route(m,p,b))})}catch(e){OCEvent('http',{id,status:500,ct:'text/plain',body:String(e)})}},4)},
 drop(){},sseStart(){sse=true;setTimeout(()=>OCEvent('sse',{t:'open'}),5)},sseStop(){},wsOpen(id){setTimeout(()=>{OCEvent('ws',{id,t:'open'});OCEvent('ws',{id,t:'msg',d:'root@phone:~# '})},5)},wsSend(id,t){(window.__wsSent=window.__wsSent||[]).push(t)},wsClose(){},
 ls:p=>JSON.stringify({ok:true,path:p,items:[{name:'app',dir:true},{name:'notes.txt',dir:false}]}),fs:()=>JSON.stringify({ok:true}),readText:()=>JSON.stringify({ok:true,text:'abc'}),writeText:()=>JSON.stringify({ok:true}),
 cfgRead:()=>window.__cfg||'{\n // comment\n "plugin":["a"],\n "mcp":{"ctx7":{"type":"remote","url":"https://m.example/mcp","enabled":true}}\n}',cfgWrite(t){window.__cfg=t;return JSON.stringify({ok:true})},readLog:()=>'log line',
 pickFile(k,d){window.__picked=[k,d]},copy(t){window.__copied=t},pasteText:()=>'pasted',share(t){window.__shared=t},openLink(u){window.__opened=u},checkUpdates(){setTimeout(()=>OCEvent('update',{core:'v1.18.6'}),5)},applyAppUpdate(){},applyCoreUpdate(){}
};
window.__onPrompt=b=>{const sid='ses_1';const P=(part)=>__push({type:'message.part.updated',properties:{part:Object.assign({sessionID:sid},part)}});
 __push({type:'session.updated',properties:{info:srv.sessions[0]}});
 __push({type:'message.updated',properties:{info:{id:b.messageID,sessionID:sid,role:'user',time:{created:Date.now()}}}});
 P({id:'prt_u1',messageID:b.messageID,type:'text',text:b.parts[0].text});
 const aid=OCM.mid();
 __push({type:'message.updated',properties:{info:{id:aid,sessionID:sid,role:'assistant',providerID:'opencode',modelID:'big-pickle',time:{created:Date.now()},tokens:{input:1200,output:300,reasoning:0,cache:{read:0,write:0}}}}});
 __push({type:'session.status',properties:{sessionID:sid,status:{type:'busy'}}});
 P({id:'prt_a1',messageID:aid,type:'reasoning',text:'thinking about it',time:{start:1}});
 P({id:'prt_a2',messageID:aid,type:'text',text:'Hel'});
 __push({type:'message.part.delta',properties:{sessionID:sid,messageID:aid,partID:'prt_a2',field:'text',delta:'lo **world**\n```py\nprint(1)\n```'}});
 P({id:'prt_a3',messageID:aid,type:'tool',tool:'bash',callID:'c1',state:{status:'running',input:{command:'ls -la',description:'List files'},metadata:{output:'total 0'},time:{start:1}}});
 setTimeout(()=>{P({id:'prt_a3',messageID:aid,type:'tool',tool:'bash',callID:'c1',state:{status:'completed',input:{command:'ls -la',description:'List files'},output:'total 0\nmain.py',metadata:{exit:0},title:'ls',time:{start:1,end:2}}});
  P({id:'prt_a4',messageID:aid,type:'tool',tool:'edit',callID:'c2',state:{status:'completed',input:{filePath:'/root/main.py'},output:'ok',metadata:{diff:'--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y'},time:{start:1,end:2}}});
  __push({type:'permission.asked',properties:{id:'per_1',sessionID:sid,permission:'bash',patterns:['rm -rf build'],metadata:{}}});
  __push({type:'session.diff',properties:{sessionID:sid,diff:[{file:'main.py',before:'x\n',after:'y\n',additions:1,deletions:1}]}});
  __push({type:'session.idle',properties:{sessionID:sid}})},60)};
})();
