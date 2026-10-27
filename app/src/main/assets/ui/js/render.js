(function(){
const O=window.OCM,h=O.h;
// ---- markdown ----
O.md=t=>{try{return DOMPurify.sanitize(marked.parse(t||'',{gfm:true,breaks:true}),{USE_PROFILES:{html:true},FORBID_TAGS:['style','form','input','button','textarea','select'],FORBID_ATTR:['style','onerror','onload']})}catch{return'<pre>'+O.esc(t)+'</pre>'}};
O.enhance=el=>{el.querySelectorAll('pre').forEach(pre=>{if(pre.dataset.e||pre.closest('.codeblock'))return;pre.dataset.e='1';
  const code=pre.querySelector('code');const lang=((code&&code.className.match(/language-([\w+#-]+)/))||[])[1]||'';
  if(code&&window.hljs){try{hljs.highlightElement(code)}catch{}}
  const wrap=h('div',{class:'codeblock'},h('div',{class:'code-bar'},h('span',null,lang||'текст'),h('button',{class:'ibtn s','data-copy':'1'},O.icon('copy'))));
  pre.replaceWith(wrap);wrap.append(pre)})};
O.mdInto=(el,t)=>{el.innerHTML=O.md(t);O.enhance(el)};
})();
