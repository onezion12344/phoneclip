# 真机验收清单

目标：用一台 Android 手机证明 1.4 的功能真实可用。全部通过后，
README 的「验证状态」才能把手机端一栏划掉。

## 0. 前置

- Mac 上 PhoneClip 在跑：菜单栏图标存在，且
  `curl -H "X-PhoneClip-Token: $(python3 -c 'import json;print(json.load(open("'"$HOME"'/Library/Application Support/PhoneClip/config.json"))["token"])')" http://127.0.0.1:47411/v1/ping`
  返回 `{"ok":true}`。
- 手机与 Mac 在同一 Tailscale 网络（或同一局域网）。
- Mac 上的 `ffmpeg` / `ffprobe` 可用（`brew install ffmpeg`）。
- macOS 防火墙若开启，允许 `python3` 接收入站连接（转写测试用本机 mock 端点）。

## 1. 让手机可被 adb 看到

任选其一：

- **USB**：开发者选项 → USB 调试 → 插线 → `adb devices` 出现设备。
- **无线调试**（ColorOS：开发者选项 → 无线调试 → 使用配对码配对）：
  `adb pair <ip>:<pair-port>`，再 `adb connect <ip>:<connect-port>`。

确认：

    adb devices            # 状态必须是 device，不能是 unauthorized
    adb shell getprop ro.product.model

## 2. 一次授权（只需一次）

App 会被自动安装并启动。首次可能需要在手机上手动允许：

- 「所有文件访问」：脚本检测不到时会自动跳到设置页，打开开关后重跑。
- 自启动：设置 → 应用 → 自启动 → 允许 OneZion Clip（不开放 adb）。
- 电池：耗电管理 → 允许后台活动。

## 3. 跑验收

    ./selftest.sh <serial>

脚本会：编译并安装当前源码的 APK、授予图片/通知权限、
推入一段 1080p 测试视频与一段音频、起本机 mock 转写端点，然后断言：

| 断言 | 通过标准 |
|---|---|
| 侧车列目录 | `GET /v1/sidecar/probe?path=/sdcard/Download` 结果含 `pc_test.mp4` |
| 侧车下载 | `~/Downloads/PhoneClip/pc_test.m4a` 出现 |
| 手机端转写 + 同步 | 配置的转录文件夹里出现 `*-pc_test.m4a.txt`，内容含 `mock transcript` |
| 720p 压缩 | `~/Downloads/PhoneClip/pc_test-720p.mp4` 宽度 = 1280 |
| AAC 压缩 | `~/Downloads/PhoneClip/pc_test-96k.m4a` 码率 ≤ 120 kbps |

最后应打印 `RESULT: 5 passed, 0 failed`。

## 4. 手工确认（脚本不覆盖）

- 用相机拍一张照片：Mac 应弹提示，剪贴板可直接粘贴该照片。
- 点「发送剪贴板」磁贴：Mac 收到手机当前复制的文字/图片。
- App 内「手机文件…」：能浏览 `/sdcard` 并下载文件。
- 杀掉 App 后重新拍照：前台服务应自动恢复（或从最近任务重新打开）。

## 5. 失败时

- `unauthorized`：在手机上确认 USB 调试授权弹窗。
- 手机连不上 Mac：`adb shell "toybox nc -z -w 2 <mac-ip> 47411"`，返回 0 才算通。
- 转写超时：macOS 防火墙拦截 mock 端点，或 Tailscale 未开。
- 压缩输出缺失：`adb logcat -s PhoneClip` 看 `compresstest` 分支的异常。
- 所有分享/压缩都提示「Mac 同步未启动」：前台服务被系统限制，
  打开 App 再操作；仍失败则看 `adb logcat | grep ForegroundServiceStartNotAllowed`。
