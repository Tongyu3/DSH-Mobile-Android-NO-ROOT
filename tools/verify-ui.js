(() => {
  const R = (el) => { const r = el.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; };
  const out = {};

  // ── 修复 4：DSH 自带的侧边栏开关是否始终隐藏 ──
  out.issue4 = [...document.querySelectorAll('button')]
      .filter(b => /^(打开侧边栏|显示侧边栏)$/.test(b.getAttribute('aria-label') || ''))
      .map(b => ({ id: b.id || null, al: b.getAttribute('aria-label'), display: getComputedStyle(b).display }));

  // ── 修复 3：抽屉是否已是 fixed + transform 过渡，栅格是否恒定 ──
  const f = document.querySelector('[data-dsh-frame]');
  const sb = document.querySelector('[class*="sidebarCol"]');
  out.issue3 = {
    frameCols: f ? getComputedStyle(f).gridTemplateColumns : null,
    frameInlineCols: f ? (f.style.gridTemplateColumns || null) : null,
    sidebar: sb ? {
      position: getComputedStyle(sb).position,
      width: getComputedStyle(sb).width,
      transition: getComputedStyle(sb).transition,
      transform: getComputedStyle(sb).transform,
      rect: R(sb),
      inlineWidth: sb.style.width || null,
      inlineOverflow: sb.style.overflow || null,
    } : null,
    centerCol: document.querySelector('[class*="centerCol"]') ? R(document.querySelector('[class*="centerCol"]')) : null,
    railHidden: document.body.getAttribute('data-dsh-rail-hidden'),
  };

  // ── 修复 1：token 浮层结构（它是 role=dialog 但没有 nav） ──
  let tokEl = null;
  document.querySelectorAll('*').forEach(el => {
    if (el.childElementCount) return;
    const t = (el.textContent || '').trim();
    if (/tok/i.test(t) && t.length < 40 && !tokEl) tokEl = el;
  });
  if (tokEl) {
    let c = tokEl, btn = null;
    for (let i = 0; i < 6 && c; i++) {
      if (c.tagName === 'BUTTON' || c.getAttribute('role') === 'button') { btn = c; break; }
      c = c.parentElement;
    }
    (btn || tokEl).click();
  } else {
    out.issue1 = 'no token element found';
  }

  return new Promise(res => setTimeout(() => {
    const d = [...document.querySelectorAll('[role="dialog"]')].find(x => !x.querySelector(':scope > nav'));
    if (d) {
      const cs = getComputedStyle(d);
      out.issue1 = {
        dialog: { display: cs.display, flexDirection: cs.flexDirection, rect: R(d) },
        children: [...d.children].map(c => ({ cls: (c.className || '').toString().slice(0, 20), rect: R(c) })),
      };
    }
    // 关掉浮层
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    res(JSON.stringify(out, null, 1));
  }, 800));
})()
