// dsh-phone —— 把安卓手机的操作能力做成 DSH 原生工具。
//
// 设计上刻意做成**薄薄一层 HTTP 客户端**：
// 真正干活的是 App 里的"手机控制桥"（127.0.0.1:3099），
// 它在 App 侧强制白名单边界，插件这边一行权限判断都不做 ——
// 也不该做，因为插件跑在容器里，改一行就能绕过自己写的检查。
// 把边界留在 App 里，是这套设计唯一安全的形态。
//
// 不使用任何第三方依赖，也不引入 @deepseek-ai/dsh-tools 的**值**导入以外的东西：
// 插件随 APK 附带、在离线容器里安装，多一个依赖就多一个装不上的理由。

import fs from 'node:fs'
import http from 'node:http'
// defineTool 不是恒等函数：它会把 execute / output.render 包装成框架认识的
// ToolDefinition。直接往 ctx.tools.register 塞普通对象是不行的 —— 实测签名是
// register(definition: ToolDefinition)，必须过这一层。
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'phone'
export const inject = ['tools']

const PORT = 3099
// token 由 App 只读绑定进容器；放在共享存储会被别的应用读到，所以优先用 /run
const TOKEN_FILES = ['/run/dsh-phone/token', '/sdcard/dsh/.phone-token']

function readToken() {
  for (const p of TOKEN_FILES) {
    try {
      const t = fs.readFileSync(p, 'utf8').trim()
      if (t) return t
    } catch (e) { /* 试下一个 */ }
  }
  return ''
}

/** 调一次控制桥。失败时抛异常 —— DSH 会把它变成给模型看的 isError。 */
function call(apiPath, params = {}, timeoutMs = 30000) {
  const token = readToken()
  if (!token) {
    throw new Error('读不到手机控制桥的 token（App 是否在运行？）')
  }
  const qs = Object.entries({ token, ...params })
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(String(v)))
    .join('&')

  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1', port: PORT, path: apiPath + '?' + qs,
      method: 'GET', timeout: timeoutMs,
    }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (d) => { body += d })
      res.on('end', () => {
        if (/^ERROR/.test(body)) reject(new Error(body.trim()))
        else resolve(body)
      })
    })
    req.on('error', (e) => reject(new Error('连不上手机控制桥：' + e.message)))
    req.on('timeout', () => { req.destroy(new Error('手机控制桥响应超时')) })
    req.end()
  })
}

/** 统一构造工具：所有工具都是"调桥 → 返回文本"。 */
function bridgeTool(spec) {
  return defineTool({
    name: spec.name,
    description: spec.description,
    parameters: spec.parameters || {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: String(value) }],
    },
    async execute(args) {
      return await call(spec.path, spec.map ? spec.map(args) : args)
    },
  })
}

const ALLOW_NOTE = '（受手机 App 内的应用白名单限制：前台应用不在白名单时会被拒绝）'

export function apply(ctx) {
  const tools = [
    bridgeTool({
      name: 'phone_status',
      description: '查看手机控制状态：当前前台应用、白名单、各项能力是否就绪。'
        + '操作手机前建议先调一次。',
      path: '/status',
    }),
    bridgeTool({
      name: 'phone_screenshot',
      description: '截取当前手机屏幕并存成 PNG，返回文件路径。'
        + '**微信 / QQ 这类屏蔽了控件树的应用，只能靠这个看**：'
        + '拿到路径后用 read_image 读它，图片才会进入你的视野。' + ALLOW_NOTE,
      path: '/shot',
    }),
    bridgeTool({
      name: 'phone_ui',
      description: '读取当前界面的控件树（带每个控件的屏幕坐标）。'
        + '如果只返回一行"前台应用"而后面什么都没有，说明这个应用屏蔽了控件树，'
        + '不要重试，改用 phone_screenshot。' + ALLOW_NOTE,
      path: '/ui',
    }),
    bridgeTool({
      name: 'phone_tap',
      description: '按屏幕坐标点击。坐标从 phone_ui 或 phone_screenshot 上判断。' + ALLOW_NOTE,
      parameters: {
        x: { type: 'number', required: true, description: '横坐标（像素）' },
        y: { type: 'number', required: true, description: '纵坐标（像素）' },
      },
      path: '/tap',
    }),
    bridgeTool({
      name: 'phone_click',
      description: '点击包含指定文字（或无障碍描述）的控件。比 phone_tap 稳，优先用它。'
        + '微信/QQ 读不到控件树时用不了，改用 phone_tap。' + ALLOW_NOTE,
      parameters: {
        text: { type: 'string', required: true, description: '控件上的文字，包含匹配' },
      },
      path: '/click',
    }),
    bridgeTool({
      name: 'phone_swipe',
      description: '从一点滑动到另一点。' + ALLOW_NOTE,
      parameters: {
        x1: { type: 'number', required: true },
        y1: { type: 'number', required: true },
        x2: { type: 'number', required: true },
        y2: { type: 'number', required: true },
        ms: { type: 'number', description: '滑动时长（毫秒），默认 300' },
      },
      path: '/swipe',
    }),
    bridgeTool({
      name: 'phone_text',
      description: '往当前获得焦点的输入框里输入文字。'
        + '会自动先试无障碍、失败再改用内置输入法 —— '
        + '**微信会拦截无障碍注入，只有输入法这种方式能进去**。'
        + '成功后默认输入法会变成「DSH 输入」，'
        + '干完活**必须**调用 phone_ime 的 restore 还回去。'
        + '如果报"没有获得焦点的输入框"，先 phone_tap 点一下输入框。' + ALLOW_NOTE,
      parameters: {
        value: { type: 'string', required: true, description: '要输入的文字' },
      },
      path: '/text',
    }),
    bridgeTool({
      name: 'phone_key',
      description: '发送全局按键。' + ALLOW_NOTE,
      parameters: {
        name: {
          type: 'string', required: true,
          enum: ['back', 'home', 'recents', 'notifications'],
          description: '要发送的按键',
        },
      },
      path: '/key',
    }),
    bridgeTool({
      name: 'phone_open',
      description: '启动白名单里的应用（按应用名或包名匹配）。'
        + '白名单外的应用一律拒绝 —— 这是用户设定的安全边界，不要尝试绕过。',
      parameters: {
        q: { type: 'string', required: true, description: '应用名或包名，例如「微信」或 com.tencent.mm' },
      },
      path: '/open',
    }),
    bridgeTool({
      name: 'phone_ime',
      description: '内置输入法的控制。op=status 看状态；'
        + 'op=activate 提前切过来；'
        + '**op=restore 把用户自己的输入法还回去 —— 用 phone_text 打完字后必须调这个**，'
        + '否则用户的手机会一直停在一个没有键盘的输入法上。',
      parameters: {
        op: {
          type: 'string',
          enum: ['status', 'activate', 'restore'],
          description: '要执行的操作，默认 status',
        },
      },
      path: '/ime',
    }),
  ]

  // 逐个注册，并把**结果写成一个自检文件**。
  //
  // 为什么值得多这一步：插件"装上了"和"工具真的注册成功"是两件事。
  // 这个项目已经栽过一次"加载了 ≠ 使用了"（WebView 垫片因为注入语句漏了变量，
  // 文件在仓库里却从来没生效，只在老机器上表现为故障）。
  // 所以这里留一份可读的自检结果，而不是靠"dsh 没崩"来推断。
  let ok = 0
  const failed = []
  for (const t of tools) {
    try {
      ctx.tools.register(t)
      ok++
    } catch (e) {
      failed.push(t.name + ': ' + (e && e.message ? e.message : String(e)))
    }
  }

  try {
    fs.mkdirSync('/sdcard/.dsh-phone', { recursive: true })
    fs.writeFileSync('/sdcard/.dsh-phone/plugin-loaded.txt',
      'plugin=dsh-phone\n'
      + 'time=' + new Date().toISOString() + '\n'
      + 'tools=' + tools.length + '\n'
      + 'registered=' + ok + '\n'
      + 'failed=' + JSON.stringify(failed) + '\n'
      + 'names=' + tools.map((t) => t.name).join(',') + '\n')
  } catch (e) {
    // 写不了自检文件不该让插件失效
  }
}
