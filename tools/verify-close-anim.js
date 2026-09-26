(() => {
  const sb = () => document.querySelector('[class*="sidebarCol"]');
  const tx = () => {
    const s = sb(); if (!s) return null;
    const m = getComputedStyle(s).transform;
    if (m === 'none') return 0;
    const p = m.match(/matrix\(([^)]+)\)/);
    return p ? parseFloat(p[1].split(',')[4]) : 0;
  };
  const wait = (ms) => new Promise(r => setTimeout(r, ms));

  function record(ms) {
    return new Promise(res => {
      const seq = []; const t0 = performance.now();
      const step = () => {
        seq.push(Math.round(tx() * 10) / 10);
        if (performance.now() - t0 < ms) requestAnimationFrame(step);
        else {
          const rev = []; let dir = 0;
          for (let i = 1; i < seq.length; i++) {
            const d = seq[i] - seq[i - 1];
            if (Math.abs(d) < 8) continue;
            const s = Math.sign(d);
            if (dir !== 0 && s !== dir) rev.push({ atFrame: i, from: seq[i - 1], to: seq[i] });
            dir = s;
          }
          res({ frames: seq.length, start: seq[0], end: seq[seq.length - 1], reversals: rev,
                path: seq.filter((v, i) => i % 3 === 0) });
        }
      };
      requestAnimationFrame(step);
    });
  }

  return (async () => {
    // 确保抽屉是开的（最多重试 3 次）
    let tries = 0;
    while (Math.abs(tx()) > 1 && tries < 3) {
      const r = document.getElementById('dsh-rail-btn');
      if (r) r.click();
      await wait(700); tries++;
    }
    const wasOpen = Math.abs(tx()) < 1;
    const recP = record(900);
    await wait(80);
    const cb = [...document.querySelectorAll('button')].find(b => (b.getAttribute('aria-label') || '') === '收起侧边栏');
    if (cb) cb.click();
    const close = await recP;
    await wait(200);
    return JSON.stringify({ wasOpen, closeBtnFound: !!cb, close, finalTx: tx() }, null, 1);
  })();
})()
