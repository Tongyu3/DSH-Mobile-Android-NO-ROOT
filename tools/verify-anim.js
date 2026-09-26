(() => {
  const sb = () => document.querySelector('[class*="sidebarCol"]');
  const tx = () => {
    const s = sb();
    if (!s) return null;
    const m = getComputedStyle(s).transform;
    if (m === 'none') return 0;
    const p = m.match(/matrix\(([^)]+)\)/);
    return p ? parseFloat(p[1].split(',')[4]) : 0;
  };

  /** 录一段 transform 轨迹，返回 {frames, reversals} */
  function record(ms) {
    return new Promise(res => {
      const seq = [];
      const t0 = performance.now();
      const step = () => {
        seq.push(Math.round(tx() * 10) / 10);
        if (performance.now() - t0 < ms) requestAnimationFrame(step);
        else {
          // 找出方向反转的帧（阈值 8px，避开浮点噪声）
          const rev = [];
          let dir = 0;
          for (let i = 1; i < seq.length; i++) {
            const d = seq[i] - seq[i - 1];
            if (Math.abs(d) < 8) continue;
            const s = Math.sign(d);
            if (dir !== 0 && s !== dir) rev.push({ i, from: seq[i - 1], to: seq[i] });
            dir = s;
          }
          res({ frames: seq.length, start: seq[0], end: seq[seq.length - 1], reversals: rev, seq: seq.filter((v, i) => i % 4 === 0) });
        }
      };
      requestAnimationFrame(step);
    });
  }

  const out = {};
  // 保证从"开着"开始
  const rail = document.getElementById('dsh-rail-btn');
  if (rail) rail.click();

  return new Promise(res => setTimeout(() => {
    out.beforeCloseTx = tx();
    record(900).then(closeRec => {
      // 在录制过程中触发收起
      const cb = [...document.querySelectorAll('button')].find(b => (b.getAttribute('aria-label') || '') === '收起侧边栏');
      out.closeBtnFound = !!cb;
      if (cb) cb.click();
      setTimeout(() => {
        out.close = closeRec;
        out.afterCloseTx = tx();
        // 再测展开
        const rec2 = record(900);
        setTimeout(() => {
          const r2 = document.getElementById('dsh-rail-btn');
          if (r2) r2.click();
        }, 60);
        rec2.then(openRec => {
          out.open = openRec;
          out.afterOpenTx = tx();
          res(JSON.stringify(out, null, 1));
        });
      }, 950);
    });
    // 60ms 后再点，确保录制已经开始
  }, 900));
})()
