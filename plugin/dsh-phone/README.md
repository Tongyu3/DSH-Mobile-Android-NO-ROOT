# dsh-phone

把**安卓手机的操作能力**做成 DeepSeek Harness 的原生工具。

装上之后，agent 不再需要靠 `bash` 去调 `phone` 命令，而是直接在工具列表里看到
`phone_screenshot`、`phone_tap`、`phone_text` 这些工具，带参数 schema、带错误说明。

## 它解决的两件事

**1. 微信 / QQ 读不出控件树 —— 用截图**

微信对无障碍和 `uiautomator` 都返回空树（实测只有 407 字节的根节点），
但屏幕像素是完整可截的。`phone_screenshot` 截图后，
用 `read_image` 读那个 PNG，图片就进入模型视野了。

> 前提：模型要支持图片输入。DeepSeek V4.1 Flash 支持；V4 Pro 是纯文本。

**2. 微信拦截直接塞字符 —— 用输入法**

微信会忽略无障碍的 `ACTION_SET_TEXT`，也让 `input text` 进不去。
`phone_text` 会自动改用**内置输入法**提交（输入法是系统认可的合法通道，微信拦不住），
成功后请务必调用 `phone_ime` 的 `restore` 把用户自己的输入法还回去。

## 安装

由 DeepSeek Harness 安卓版**自动安装**（随 APK 附带）。
手动安装：

```sh
dsh plugin --profile web add ./dsh-phone-1.0.0.tgz
```

## 安全边界

**所有操作都受 App 内的应用白名单限制。** 前台应用不在白名单里时，
每个工具都会返回一句明确的拒绝说明 —— 包括截图（屏幕上可能有聊天记录、验证码）。

这条边界在 App 侧强制，插件无法绕过。

## 工具清单

| 工具 | 作用 |
|---|---|
| `phone_status` | 服务状态、当前前台应用、白名单 |
| `phone_screenshot` | 截屏并存成 PNG，返回路径（配合 `read_image`） |
| `phone_ui` | 读控件树（微信/QQ 会返回空树，改用截图） |
| `phone_tap` | 按坐标点击 |
| `phone_click` | 按文字/描述点击（比坐标稳） |
| `phone_swipe` | 滑动 |
| `phone_text` | 输入文字（自动切换输入法） |
| `phone_key` | 返回 / 主页 / 多任务 / 通知栏 |
| `phone_open` | 启动白名单内的应用 |
| `phone_ime` | 输入法状态 / 切过来 / 还回去 |

## 许可

MIT。本插件是 DeepSeek Harness 的第三方插件，与 DeepSeek 官方无关。
