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
const crash = args.includes('--crash');
let expr;
if (args[0] === '--file') {
  expr = readFileSync(args[1], 'utf8');
} else if (!crash) {
  expr = args[0];
}

const targets = await (await fetch('http://127.0.0.1:9222/json')).json();
/*
 * 取**最后一个** page target，而不是第一个。
 *
 * 原因：WebView 在发生导航/渲染进程交换时会同时留下新旧两个 target
 * （实测点"在侧边栏预览"之后就是这样，两个 target 标题一模一样）。
 * 第一个往往是已经作废的那个，连上去只会得到
 * "Inspected target navigated or closed"。
 * 需要指定时用环境变量 CDP_TARGET=<序号>。
 */
const pages = targets.filter(t => t.type === 'page');
const want = process.env.CDP_TARGET === undefined ? pages.length - 1 : Number(process.env.CDP_TARGET);
const page = pages[want] ?? pages[pages.length - 1];
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
    if (crash) {
      /*
       * --crash：主动让 WebView 的渲染进程崩溃。
       *
       * 用途：验证 App 侧 onRenderProcessGone 的自愈 —— 用户反馈的"用久了白屏"
       * 第一大成因就是渲染进程被系统回收，而这条路没法用 adb 直接模拟
       * （不是 root，杀不了别的 uid 的进程）。Page.crash 能精确触发它。
       *
       * 渲染进程一死，这条 WebSocket 也会跟着断 —— 所以"没收到回包"是正常的，
       * 不要当成失败。
       */
      try { await send('Page.enable'); } catch { }
      await send('Page.crash').catch(() => { });
      console.log('已发送 Page.crash（连接随渲染进程断开属正常）');
      process.exit(0);
    }
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
