# DeepSeek Harness for Android

**把 [DeepSeek Harness](https://github.com/deepseek-ai)（DSH）完整装进安卓手机里运行的 App。**

装好之后 DSH 就跑在手机本地的一个 Linux 容器里 —— 不需要电脑、不需要服务器、不需要 root。

> ### ⚠️ 非官方声明
> 本项目是**第三方非官方封装**，与 DeepSeek **没有任何隶属、合作或背书关系**。
> "DeepSeek"、"DeepSeek Harness" 等名称归其权利人所有，此处仅用于说明兼容对象。
> 本项目按 MIT 发布，**不提供任何担保**，请自行判断风险。

<p align="center">
  <img src="docs/img/03-workspace.png" width="240">
  <img src="docs/img/05-allowlist.png" width="240">
</p>

---

## 这是什么

一个把「proot + Ubuntu + Node.js + DSH + WebView」打包在一起的安卓 App：

```
┌─────────────────── 安卓 App (本仓库) ───────────────────┐
│  MainActivity ── WebView ──► http://127.0.0.1:3080      │
│       │                                                  │
│       └─ DshService（前台服务，保活）                    │
│              └─ proot ── Ubuntu rootfs ── Node ── dsh web│
│                                                          │
│  可选：无障碍服务 + 回环控制桥 → 让 agent 操作指定 App   │
└──────────────────────────────────────────────────────────┘
```

**首次启动**会自动下载并解压约 150 MB（Ubuntu base + Node.js + `npm i -g @deepseek-ai/dsh`），
大约 1~3 分钟。之后就是秒开。

## 能力

- 完整 DSH Web UI，对话 / 附件上传 / 工作区切换
- 读写手机文件（`/sdcard` 即内部存储）
- **可选**：让 agent 操作你指定的 App（无障碍 + 白名单，名单外一律拒绝）
- **无障碍守护**：厂商把无障碍开关关掉时自动写回（实测约 0.3 秒恢复，**无次数上限**）
- **两种操作后端可切换**：无障碍（快）或 直接命令 / Shizuku（不被厂商策略影响）
- **兼容老 WebView**：内置垫片补齐 `Iterator` / `AbortSignal.any` 等新 API
- 手机与平板同一个包（宽屏自动跳过窄屏专属布局补丁）
- 全部离线自持：容器、Node、DSH 都在手机本地

## 限制（请先看这里）

| 限制 | 说明 |
|---|---|
| **仅 arm64** | 只带了 arm64 的 proot 与 Node |
| **Android 7.0+** | `minSdk 24` |
| **`targetSdk` 固定 28** | Android 10+ 的 W^X 禁止 targetSdk≥29 的应用执行自己数据目录里的二进制。这是硬要求，**因此永远无法上架 Google Play**，只能侧载 |
| **QQ / 微信的界面读不出来** | 实测这两个 App 对 `uiautomator` 和无障碍**两条路都返回空树**（微信只有 407 字节的根节点），是它们自己屏蔽的。所以这两者目前无法自动化，其他 App 不受影响 |
| **vivo / iQOO 会关无障碍** | 这类 App 一进前台，vivo 会把无障碍主开关置 0。App 会**自动补回**（见下），但补回后仍受上一条限制 |
| 首次启动需联网 | 约 150 MB，且需要自备 DeepSeek API Key |

### 关于无障碍守护需要的一次性授权

要自动把无障碍开关写回去，需要 `WRITE_SECURE_SETTINGS`。它的保护级别带
`development` 标志，因此**可以用 `pm grant` 单独授予一次，且永久有效**
（写进 `packages.xml`，重启手机、更新 App 都不会掉）：

```bash
adb shell pm grant com.dshmobile.probe android.permission.WRITE_SECURE_SETTINGS
```

没有电脑的用户可以走 **Shizuku**（它以 shell 身份代跑这条命令），
详见 [docs/Shizuku授权教程.md](docs/Shizuku授权教程.md)。

> ⚠️ **任何 APK 都不可能"自带最高权限"** —— Shizuku 的权限来自 ADB 或 root，
> 是从应用外部拿进来的。详见上面那份教程的开头。
>
> 没有这个授权也能正常用，只是无障碍被关掉后需要手动去设置里重开。

## 安装（普通用户）

去 [**Releases**](../../releases) 下载最新的 `DeepSeek-Harness-*.apk`，传到手机点击安装。

详细的图文步骤（含 vivo「超级守护」怎么过、API Key 填在哪、工作区怎么选）见
👉 **[docs/安装与使用说明.md](docs/安装与使用说明.md)**

## 从源码构建

需要 **JDK 17** 和 Android SDK（`compileSdk 36`、`build-tools 36.0.0`）。

```bash
git clone https://github.com/<你的用户名>/<仓库名>.git
cd <仓库名>

# 指向你的 Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 打一个可分发（release 签名）的包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

**发布签名**：仓库**不含**任何密钥。要出正式包，在项目根放一个 `keystore.properties`：

```properties
storeFile=release.keystore
storePassword=你的口令
keyAlias=dshmobile
keyPassword=你的口令
```

没有这个文件也能构建，只是 release 包不会被签名（等同于未签名包）。

> `debug` 与 `release` 共用同一签名配置，所以两者可以互相覆盖安装、不会清空 App 数据
> （方便排查界面问题）。详见 `app/build.gradle`。

### 中国大陆用户

Gradle wrapper 默认指向腾讯镜像（`mirrors.cloud.tencent.com`），开箱可用。
境外用户若觉得慢，改 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl`
为 `https://services.gradle.org/distributions/gradle-8.14.5-bin.zip` 即可。

### 目录说明

| 路径 | 作用 |
|---|---|
| `app/src/main/java/.../Env.java` | 容器环境：proot 参数、挂载、DSH 启动命令 |
| `app/src/main/java/.../DshService.java` | 前台服务 + 监督循环 |
| `app/src/main/java/.../MainActivity.java` | WebView、自检、文件选择器、JS 桥 |
| `app/src/main/java/.../PhoneBridge.java` | 手机控制回环桥（token + 白名单） |
| `app/src/main/java/.../DshAccessibilityService.java` | 无障碍服务（动作 + 界面树） |
| `app/src/main/java/.../Priv.java` | 无障碍开关的读/写（自愈的能力层） |
| `app/src/main/java/.../A11yGuard.java` | 无障碍守护看门狗（事件驱动 + 轮询，无次数上限） |
| `app/src/main/java/.../ShizukuBridge.java` | Shizuku 客户端（权限流 + 执行入口） |
| `app/src/main/java/.../ShellUserService.java` | 以 shell 身份跑在独立进程里的命令服务 |
| `app/src/main/aidl/.../IShellService.aidl` | 上面那个服务的接口（`destroy()` 事务码必须是 16777114） |
| `app/src/main/java/.../ShellControl.java` | 「直接命令」后端（`uiautomator dump` + `input`，不依赖无障碍） |
| **`tools/patch.js`** | **前端补丁的源文件**（构建时自动同步成 `app/src/main/assets/dsh-mobile.js`） |
| **`app/src/main/assets/webview-shim.js`** | **WebView 兼容垫片**（补齐 `Iterator` / `AbortSignal.any` 等新 API） |
| `tools/cdp.mjs` | 用 Chrome DevTools 协议在 PC 上调试 WebView 的小工具 |
| `tools/make-source-package.ps1` | 重新生成可上传的源码包 |

> `tools/patch.js` 是**单一事实来源**：`app/src/main/assets/dsh-mobile.js` 由 Gradle 的
> `syncMobilePatch` 任务在构建时自动生成，**不要直接改 assets 里那份**。
> 补丁全部是注入式的，不修改容器里 DSH 的任何文件，所以 DSH 升级后依然有效。

> ⚠️ **`webview-shim.js` 必须在 `onPageStarted` 里真的注入进去。**
> 它曾经因为注入语句漏掉变量而**长期没有生效**（文件在仓库里、编译无警告、
> 只在老机器上表现为 `Iterator is not defined` / `AbortSignal.any is not a function`）。
> 改动这段注入逻辑后，请用 `SIMULATE_OLD_WEBVIEW` 做一次对照实验：
> 先删掉这些 API 复现故障，再确认垫片能把它们补回来。
> 正式构建前 `SIMULATE_OLD_WEBVIEW` 必须复位为空字符串。

## 改名（去掉品牌）

只需要改一行 `app/src/main/res/values/strings.xml`：

```xml
<string name="app_name">DSH Mobile</string>
```

改显示名不影响包名，因此不会导致容器里的 rootfs / Node / DSH 需要重装。

## 隐私与安全

这个 App 要的权限看着吓人，所以说清楚它到底做什么：

| 权限/能力 | 用途 | 边界 |
|---|---|---|
| 存储读写 | 容器通过 proot 绑定 `/storage/emulated/0`，才能读写你的文件 | 只访问你指定路径；App 不主动上传任何文件 |
| 无障碍 | 让 agent 读取界面并点击 | **只对你自己勾选的白名单应用生效**；名单外连界面树都读不到 |
| 前台服务 | 让容器在后台存活 | 通知栏常驻 |

- **API Key 只存在手机本地的 App 私有目录**，由 App 作为环境变量传给容器里的 DSH，
  最终由 DSH 直接请求 `api.deepseek.com`。本项目没有任何自有服务器，代码可自行审计。
- 控制桥只监听 `127.0.0.1:3099`，需带随机 token；token 通过**只读绑定**进容器，
  不放共享存储（否则其他 App 能读到）。
- 不 root、不改系统设置。

## 许可证

- 本仓库自身代码：**MIT**，见 [LICENSE](LICENSE)
- 打包进 APK 的第三方二进制（**proot 为 GPLv2** 等）：见 [THIRD-PARTY.md](THIRD-PARTY.md) 与 [LICENSES/](LICENSES/)

---

# English

**Run [DeepSeek Harness](https://github.com/deepseek-ai) (DSH) entirely on an Android phone.**
No PC, no server, no root — DSH runs inside a local Linux container (proot + Ubuntu + Node.js)
and its web UI is shown in a WebView.

> ### ⚠️ Unofficial
> This is a **third-party, unofficial packaging**. It is **not affiliated with, endorsed by,
> or connected to DeepSeek** in any way. "DeepSeek" and "DeepSeek Harness" are trademarks of
> their respective owners and are used here only to describe what this app runs.
> Provided as-is, **with no warranty**.

**Requirements:** arm64 device, Android 7.0+, ~150 MB free space, your own DeepSeek API key.
On first launch the app downloads and unpacks Ubuntu base + Node.js + DSH (~1–3 min).

**Install:** grab the latest APK from [Releases](../../releases).
See [docs/安装与使用说明.md](docs/安装与使用说明.md) for a step-by-step guide (screenshots included).

**Build:**

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
```

**Known limitations:**

- `targetSdk` is pinned to **28** on purpose — Android 10+ W^X forbids apps with
  `targetSdk >= 29` from executing binaries in their own data directory (Termux does the same).
  Consequence: **this app can never ship on Google Play**, sideload only.
- On **vivo/iQOO**, the system force-disables accessibility services when QQ/WeChat come to the
  foreground (anti-fraud policy), so those two apps can't be automated there. Other apps are fine.
- arm64 only.

**License:** MIT for this repository's own code; bundled third-party binaries
(**proot is GPLv2**) are covered in [THIRD-PARTY.md](THIRD-PARTY.md).
