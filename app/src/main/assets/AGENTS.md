# 这是手机上的工作区

本工作区运行在一台 **Android 手机** 上：DSH 跑在应用内部的 Linux 容器里，
手机共享存储挂载在 `/sdcard`（本工作区就是 `/sdcard/dsh`）。

## 1. 文件访问

`/sdcard` 就是手机的内部存储根目录，可以直接读写，例如：

```bash
ls /sdcard
cat /sdcard/Download/some.txt
```

容器本身跑在 `DSH_PERMISSION_MODE=danger-full-access` 下，文件操作不受工作区限制。

## 2. 操作手机上的 App（重要能力）

容器里有一个命令行工具 `phone`，可以**读取屏幕内容并操作应用**：

```bash
phone status        # 查看服务状态、当前前台应用、白名单
phone apps          # 列出允许操作的应用
phone open "QQ"     # 启动白名单里的应用（按应用名或包名匹配）
phone ui            # 读取当前界面（控件树 + 每个控件的屏幕坐标）
phone click "登录"   # 点击包含该文字（或无障碍描述）的控件
phone tap 540 1200  # 按屏幕坐标点击
phone swipe 540 1800 540 600    # 滑动
phone text "hello"  # 往当前获得焦点的输入框写文字
phone key back      # 全局按键：back / home / recents / notifications
```

### 使用流程（推荐）

1. `phone status` —— 确认无障碍服务已开启，且**当前前台应用在白名单里**；
   不在的话先 `phone open "应用名"` 把它切到前台（同样受白名单限制）；
2. `phone ui` —— 读取界面，拿到控件文字与坐标；
3. `phone click "文字"` 优先于 `phone tap x y`（文字更稳，坐标会随分辨率变化）；
4. 操作后再 `phone ui` 一次，确认界面确实变了。

### 硬性限制（用户设定，无法绕过）

- **只能操作白名单里的 App。** 如果当前前台应用不在白名单，`phone` 的所有操作
  （包括 `phone ui`）都会被拒绝并返回一句错误说明。
  遇到这种情况：告诉用户需要先打开目标应用，或在 App 的
  「设置 → 📱 手机控制」里把该应用加入白名单。
- 不具备 root 权限，也无法修改系统设置。
- 不要尝试猜测或规避白名单——这是用户明确设定的安全边界。

## 3. 示例

> 用户说："帮我在微信里给张三发一条消息"

```bash
phone status                       # 看前台是不是微信、微信在不在白名单
# 如果前台不是微信，请用户先切到微信（或让用户手动打开）
phone ui | head -40                # 看界面结构
phone click "通讯录"
phone click "张三"
phone click "发消息"
phone text "你好"
phone click "发送"
phone ui | head -20                # 确认已发出
```

如果 `phone status` 显示目标应用不在白名单，直接告知用户去添加，不要绕路。
