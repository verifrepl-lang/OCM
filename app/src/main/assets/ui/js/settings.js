(function(){
const O=window.OCM,h=O.h,bus=O.bus,S=O.S;

O.openModels=()=>{
  const list=S.catalog||[];
  const body=h('div',null);
  if(!list.length)body.append(h('p',{class:'mut'},'Загружаю каталог моделей…'));
  const cur=S.model;
  const mk=(m)=>{
    const on=m.id===cur;
    return h('button',{class:'li'+(on?' on':''),onclick:()=>{
      S.model=m.id;O.store.set('model',m.id);O.native.setModel(m.id);
      bus.emit('meta');sheet.close();O.toast('Модель: '+(m.name||m.id),1500);
    }},O.icon('cpu'),h('div',{class:'t'},h('b',null,m.name||m.id),h('span',null,m.id+(m.free?' · бесплатно':' · нужен ключ'))),on?O.icon('check'):null);
  };
  const refresh=()=>{
    const free=list.filter(m=>m.free),paid=list.filter(m=>!m.free&&S.hasKey);
    body.innerHTML='';
    if(free.length){body.append(h('div',{class:'grp'},'Бесплатные'));free.forEach(m=>body.append(mk(m)))}
    if(paid.length){body.append(h('div',{class:'grp'},'С ключом'));paid.forEach(m=>body.append(mk(m)))}
    if(!free.length&&!paid.length)body.append(h('p',{class:'mut'},'Каталог пуст — проверьте сеть'));
  };
  refresh();
  const sheet=O.sheet('Модель',body);
};

O.openSettings=()=>{
  const st=O.native.state();
  const body=h('div',null);

  // --- model ---
  body.append(h('div',{class:'grp'},'Модель'));
  body.append(h('button',{class:'li',onclick:()=>{sheet.close();O.openModels()}},
    O.icon('cpu'),h('div',{class:'t'},h('b',null,'Выбор модели'),h('span',null,S.model||'—'))));
  body.append(O.toggleRow('route','Фолбэк по моделям','Если у модели кончился лимит — пробовать следующую бесплатную',S.opts.fallback,v=>{S.opts.fallback=v;O.saveOpts()}));

  // --- key ---
  body.append(h('div',{class:'grp'},'Ключ opencode Zen (необязательно)'));
  const keyWrap=h('div',{class:'field'});
  const keyInp=h('input',{type:'password',placeholder:'sk-… — с ключом лимиты IP не действуют'});
  keyInp.value=O.native.getKey();
  keyWrap.append(keyInp);
  body.append(keyWrap);
  body.append(h('button',{class:'li',onclick:()=>{
    O.native.setKey(keyInp.value.trim());S.hasKey=!!keyInp.value.trim();
    O.toast(S.hasKey?'Ключ сохранён':'Ключ удалён',1500);sheet.close();
  }},O.icon('key'),h('div',{class:'t'},h('b',null,'Сохранить ключ'),h('span',null,'Хранится только на телефоне'))));
  body.append(h('button',{class:'li',onclick:()=>O.native.open('https://opencode.ai/zen')},
    O.icon('link'),h('div',{class:'t'},h('b',null,'Получить ключ'),h('span',null,'opencode.ai/zen'))));

  // --- notifications / sound ---
  body.append(h('div',{class:'grp'},'Уведомления'));
  body.append(O.toggleRow('bell','Уведомления системы','Показывать уведомления Android',st.perms&&st.perms.notifications,v=>{if(v)O.native.grant('notifications')}));
  body.append(O.toggleRow('snd_done','Звук «ответ готов»','Короткий сигнал, когда ответ пришёл',!!S.opts.snd,v=>{S.opts.snd=v;O.saveOpts()}));

  // --- battery ---
  body.append(h('div',{class:'grp'},'Энергосбережение'));
  body.append(h('button',{class:'li',onclick:()=>O.native.grant('battery')},
    O.icon('bolt'),h('div',{class:'t'},h('b',null,'Без ограничений батареи'),h('span',null,'Чтобы поток не обрывался в фоне'))));

  // --- updates ---
  body.append(h('div',{class:'grp'},'Обновления'));
  const updRow=h('button',{class:'li',onclick:()=>{
    O.toast('Проверяю…',1200);O.native.checkUpdates();
  }},O.icon('refresh'),h('div',{class:'t'},h('b',null,'Проверить обновления'),h('span',null,'OpenCode '+st.version+' (сборка '+st.code+')')));
  body.append(updRow);

  // --- about ---
  body.append(h('div',{class:'grp'},'О приложении'));
  body.append(h('div',{class:'li static'},O.icon('info'),h('div',{class:'t'},
    h('b',null,'OpenCode Mobile — Zen Direct'),
    h('span',null,'Запросы идут с телефона напрямую в opencode.ai по протоколу /inference. Сессии хранятся только на устройстве.'))));
  body.append(h('button',{class:'li',onclick:()=>O.native.open('https://t.me/OpenCodeMob')},
    O.icon('send'),h('div',{class:'t'},h('b',null,'Telegram-канал'),h('span',null,'@OpenCodeMob'))));

  const sheet=O.sheet('Настройки',body);
};
bus.on('update',d=>{
  if(d.error){O.toast('Обновления: '+d.error,3000);return}
  if(d.pct!=null){O.toast('Скачиваю: '+d.pct+'%',800);return}
  if(d.app)O.confirm('Обновление','Доступна версия '+d.app.name+'. Скачать и установить?','Обновить').then(ok=>{if(ok)O.native.applyAppUpdate()});
  else O.toast('У вас последняя версия',2000);
});
})();
