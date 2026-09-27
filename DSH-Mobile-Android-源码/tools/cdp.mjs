// 通过 Chrome DevTools 协议在 PC 上检查手机 WebView 里的 DSH 页面。
//
// 用法:
//   node cdp.mjs --file <表达式文件>     # 执行文件里的 JS 表达式并打印结果
//   node cdp.mjs "<表达式>"              # 直接执行
//
// 依赖: Node 21+ 内置的 fetch / WebSocket（本机 v24，无需 npm install）。
// 前置: adb forward tcp:9222 localabstract:webview_devtools_remote_<appPid>
import { readFileSync } from 'node:fs';

const args = process.argv.slice(2);
let expr;
if (args[0] === '--file') {
  expr = readFileSync(args[1], 'utf8');
} else {
  expr = args[0];
}

const targets = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = targets.find(t => t.type === 'page');
if (!page) { console.error('没有找到 page target'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
let seq = 0;

function send(method, params) {
  return new Promise((resolve, reject) => {
    const myId = ++seq;
    const onMsg = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id === myId) {
        ws.removeEventListener('message', onMsg);
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      }
    };
    ws.addEventListener('message', onMsg);
    ws.send(JSON.stringify({ id: myId, method, params }));
    setTimeout(() => reject(new Error('CDP 超时')), 20000);
  });
}

ws.addEventListener('open', async () => {
  try {
    const r = await send('Runtime.evaluate', {
      expression: expr,
      returnByValue: true,
      awaitPromise: true,
    });
    if (r.exceptionDetails) {
      console.error('页面内异常:', JSON.stringify(r.exceptionDetails, null, 2));
      process.exit(2);
    }
    const v = r.result?.value;
    console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 2));
    ws.close();
    process.exit(0);
  } catch (e) {
    console.error('失败:', e.message);
    process.exit(3);
  }
});

ws.addEventListener('error', (e) => { console.error('WebSocket 错误', e.message ?? ''); process.exit(4); });
