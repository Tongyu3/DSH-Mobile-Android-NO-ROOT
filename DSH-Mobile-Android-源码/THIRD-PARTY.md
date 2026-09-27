# 第三方组件声明 / Third-Party Notices

本 APK 里打包了下列第三方二进制与库。**它们的许可证不属于本项目的 MIT 许可范围**，
各自归其作者所有。

发布本 APK 时请一并附带本文件与 `LICENSES/` 目录下的许可证全文。

---

## 1. proot —— ⚠️ GPLv2（强 copyleft，必须合规）

| 项目 | 内容 |
|---|---|
| 用途 | 容器核心。用 ptrace 做路径翻译 + uid 伪装，让 Linux rootfs 能在无 root 的安卓上跑 |
| 版本 | proot 5.1.107.95（Termux 构建，含 `--link2symlink`） |
| 许可证 | **GNU General Public License v2** |
| 许可证全文 | [`LICENSES/GPL-2.0.txt`](LICENSES/GPL-2.0.txt) |
| 上游源码 | https://github.com/proot-me/proot |
| 本二进制来源 | Termux 软件源 `proot` 包（aarch64） |
| 修改 | **未修改**，原样分发 |

GPLv2 对"分发二进制"的要求是：**附许可证全文**（已附）+ **提供对应源码**（上表已给出
上游地址与确切版本）。若你重新构建了 proot，则必须同时提供你自己的源码。

> **为什么本项目的代码可以是 MIT？**
> 本 App 与 proot 是**两个独立程序**：App 通过 `ProcessBuilder` 以**子进程**方式
> exec proot，二者之间没有任何链接（不共享地址空间、不调用彼此的函数）。
> 这在 GPL 的语境下属于 **mere aggregation（单纯聚合）**，不会让 App 自身被 copyleft 传染。
> 但**分发**这个 APK 的人（也就是你）仍需为 proot 履行 GPLv2 义务。
> 这是技术性理解，不构成法律意见。

---

## 2. libtalloc

| 项目 | 内容 |
|---|---|
| 用途 | proot 的运行时依赖（内存池） |
| 许可证 | **LGPL-3.0-or-later** |
| 许可证全文 | [`LICENSES/LGPL-3.0.txt`](LICENSES/LGPL-3.0.txt) |
| 上游源码 | https://git.samba.org/?p=talloc.git |

---

## 3. libandroid-shmem

| 项目 | 内容 |
|---|---|
| 用途 | proot 在 Android 上的共享内存依赖 |
| 许可证 | **MIT** |
| 版权 | Copyright (c) 2016-2022 Termux |
| 上游源码 | https://github.com/termux/libandroid-shmem |

MIT 许可证全文见 [`LICENSES/MIT-libandroid-shmem.txt`](LICENSES/MIT-libandroid-shmem.txt)。

---

## 4. Apache Commons Compress

| 项目 | 内容 |
|---|---|
| 用途 | 解压 Ubuntu base 的 `.tar.gz`（Android 没有内置 tar 能力） |
| 版本 | 1.26.2 |
| 许可证 | **Apache License 2.0** |
| 许可证全文 | [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt) |
| 上游 | https://commons.apache.org/proper/commons-compress/ |
| NOTICE | 本项目未对该库做任何修改；未包含其 NOTICE 文件中要求的额外归属条目 |

---

## 不在 APK 里、但首次运行时会从官方源下载的组件

这些**不随 APK 分发**，是用户设备上的包管理器/下载器从官方地址拉取的，
因此不构成本项目的再分发行为。在此列出仅为说明完整依赖：

| 组件 | 许可证 | 来源 |
|---|---|---|
| Ubuntu Base 24.04（arm64 rootfs） | 各自自由软件许可 | `cdimage.ubuntu.com` / 阿里云镜像 |
| Node.js v22.23.2 | MIT | `nodejs.org` / 淘宝镜像 |
| DeepSeek Harness（`@deepseek-ai/dsh`） | 以官方仓库声明为准 | npm registry |

---

## 商标声明 / Trademark

本项目是**非官方**的第三方封装，与 **DeepSeek 没有任何隶属或背书关系**。
"DeepSeek"、"DeepSeek Harness" 等名称与标识归其各自权利人所有，
本项目仅在**描述兼容对象**的意义上使用这些名称。

如果你要正式发布，建议把应用显示名改成中性的名字（例如 `DSH Mobile`），
只保留一句"用于运行 DeepSeek Harness"。改法见 `README.md` 的「改名」一节 ——
只需要动 `app/src/main/res/values/strings.xml` 一行。
