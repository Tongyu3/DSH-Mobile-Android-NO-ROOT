#!/opt/node-v22.23.2-linux-arm64/bin/node
/*
 * phone —— 让 agent 操作手机的控制台工具。
 *
 * 它只是 App 内"手机控制桥"（127.0.0.1:3099）的薄封装：
 *   · token 从 /run/dsh-phone/token 读取（该目录由 App 只读绑定进来，
 *     放在共享存储会被其它应用读到）；
 *   · 真正的权限边界在 App 侧的**应用白名单**：不在名单里的应用，
 *     桥会直接拒绝，本工具只会拿到一句错误说明。
 *
 * 用法：phone <命令> [参数]
 */
'use strict';
const http = require('http');
const fs = require('fs');

const PORT = 3099;
const TOKEN_FILES = ['/run/dsh-phone/token', '/sdcard/dsh/.phone-token'];

function readToken() {
  for (const p of TOKEN_FILES) {
    try {
      const t = fs.readFileSync(p, 'utf8').trim();
      if (t) return t;
    } catch (e) { /* 试下一个 */ }
  }
  return '';
}

const TOKEN = readToken();

function call(apiPath, params, cb) {
  if (!TOKEN) {
    console.error('ERROR: 读不到控制桥 token（App 是否在运行？）');
    process.exit(2);
  }
  const qs = Object.entries(Object.assign({ token: TOKEN }, params || {}))
    .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
    .join('&');
  const req = http.request({
    host: '127.0.0.1', port: PORT, path: apiPath + '?' + qs, method: 'GET', timeout: 15000,
  }, (res) => {
    let body = '';
    res.setEncoding('utf8');
    res.on('data', (d) => { body += d; });
    res.on('end', () => cb(null, body));
  });
  req.on('error', (e) => cb(e));
  req.on('timeout', () => { req.destroy(new Error('请求超时')); });
  req.end();
}

const USAGE = `phone —— 操作手机（受 App 白名单限制）

用法:
  phone status                     查看服务状态、当前前台应用、白名单
  phone apps                       列出白名单应用
  phone open "应用名或包名"         启动白名单里的应用（如 phone open QQ）
  phone ui                         读取当前界面（控件树 + 坐标）
  phone shot                       截屏并保存为图片（**看微信/QQ 只能靠这个**）
  phone tap X Y                    按坐标点击
  phone click "文字"                点击包含该文字/描述的控件
  phone swipe X1 Y1 X2 Y2 [毫秒]    滑动（默认 300ms）
  phone text "内容"                 往当前焦点输入框写文字（微信会自动改用输入法）
  phone ime [activate|restore]     输入法状态 / 切过来 / 还回去
  phone key back|home|recents|notifications

注意: 只能操作**白名单里**的应用。前台不在白名单时，所有操作（含 phone ui）都会被拒绝。
phone open 也只能启动白名单内的应用。
`;

const argv = process.argv.slice(2);
const cmd = argv[0];
const rest = argv.slice(1);

function done(err, body) {
  if (err) { console.error('ERROR: ' + err.message); process.exit(1); }
  process.stdout.write(body);
  if (/^ERROR/.test(body)) process.exit(1);
}

switch (cmd) {
  case 'status': call('/status', {}, done); break;
  case 'apps': call('/apps', {}, done); break;
  case 'ui': call('/ui', {}, done); break;
  case 'shot': call('/shot', {}, done); break;
  case 'ime': call('/ime', { op: rest[0] || 'status' }, done); break;
  case 'tap':
    if (rest.length < 2) { console.error('用法: phone tap X Y'); process.exit(1); }
    call('/tap', { x: rest[0], y: rest[1] }, done);
    break;
  case 'click':
    if (!rest.length) { console.error('用法: phone click "文字"'); process.exit(1); }
    call('/click', { text: rest.join(' ') }, done);
    break;
  case 'swipe':
    if (rest.length < 4) { console.error('用法: phone swipe X1 Y1 X2 Y2 [毫秒]'); process.exit(1); }
    call('/swipe', { x1: rest[0], y1: rest[1], x2: rest[2], y2: rest[3], ms: rest[4] || 300 }, done);
    break;
  case 'text':
    if (!rest.length) { console.error('用法: phone text "内容"'); process.exit(1); }
    call('/text', { value: rest.join(' ') }, done);
    break;
  case 'key':
    if (!rest.length) { console.error('用法: phone key back|home|recents|notifications'); process.exit(1); }
    call('/key', { name: rest[0] }, done);
    break;
  case 'open':
    if (!rest.length) { console.error('用法: phone open "应用名或包名"'); process.exit(1); }
    call('/open', { q: rest.join(' ') }, done);
    break;
  default:
    process.stdout.write(USAGE);
    process.exit(cmd ? 1 : 0);
}
