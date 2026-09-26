(() => {
  const out = { steps: [] };
  const sb = () => document.querySelector('[class*="sidebarCol"]');
  const snap = (t) => {
    const s = sb();
    const r = s.getBoundingClientRect();
    out.steps.push({
      t,
      railHidden: document.body.getAttribute('data-dsh-rail-hidden'),
      transform: getComputedStyle(s).transform,
      x: Math.round(r.x),
    });
  };

  snap('start');
  // 打开抽屉
  document.getElementById('dsh-rail-btn').click();

  return new Promise(res => {
    setTimeout(() => snap('open+70ms (动画中)'), 70);
    setTimeout(() => snap('open+500ms (稳定)'), 500);
    setTimeout(() => {
      // 点「新建会话」，看抽屉会不会自己收起
      const nb = [...document.querySelectorAll('button')].find(b => (b.getAttribute('aria-label') || '') === '新建会话');
      out.newSessionFound = !!nb;
      if (nb) nb.click();
      setTimeout(() => snap('newSession+150ms'), 150);
      setTimeout(() => snap('newSession+900ms'), 900);
      setTimeout(() => {
        // 再测一次：打开 → 收起 的中间帧
        document.getElementById('dsh-rail-btn') && document.getElementById('dsh-rail-btn').click();
        setTimeout(() => {
          snap('reopen+70ms');
          const cb = [...document.querySelectorAll('button')].find(b => (b.getAttribute('aria-label') || '') === '收起侧边栏');
          if (cb) cb.click();
          setTimeout(() => snap('collapse+70ms (动画中)'), 70);
          setTimeout(() => { snap('collapse+500ms'); res(JSON.stringify(out, null, 1)); }, 500);
        }, 600);
      }, 1000);
    }, 900);
  });
})()
