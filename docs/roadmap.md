# Roadmap / 待办

记录 owner 提出的后续方向，避免散落在对话里。当前版本：1.4.18（OneZion Connect）。

## 已在 1.4 完成

- 照片自动同步到 Mac 剪贴板（前台服务 + MediaStore 观察）
- 手机 → Mac：快捷设置磁贴 / 分享面板 / App 内发送
- Mac → 手机：文字、图片
- Sidecar：免 ADB 浏览与下载手机全盘文件（走 App 自己的 HTTP 通道）
- 手机端转写：OpenAI 兼容端点，可在手机上配置，可选同步到 Mac 自选文件夹
- 手机端压缩：720p H.264、AAC 96k
- GitHub Releases 远端更新 + 24h 自动检查
- Mac 原生菜单栏 App（无 Electron）

## 下一步：连接与 ADB

owner 目标：先把「连接侧」打通，并具备一些 ADB 能力。

事实与边界：

- **App 无法自行开启 ADB（无 root）**。`adbd` 的 TCP 模式只能由以下方式开启：
  用户在开发者选项里打开「无线调试」（重启后通常要重开并重新配对）、
  宿主执行 `adb tcpip 5555`、或 root 下 `setprop service.adb.tcp.port 5555` 后重启 adbd。
- **常驻不依赖 ADB**：App 的前台服务通过 Tailscale 长轮询 Mac，已经是常驻通道。
  ADB 只是在需要 shell 级操作（`input`、`screencap`、`appops`、批处理）时才用。
- 设备差异：Ace 3 Pro（PJX110，未 root）只能靠无线调试/USB；
  OnePlus Open（CPH2551，已 root）可以由 App 通过 `su` 持久化 `persist.adb.tcp.port`。

已做（1.4.19）：

1. `Adb`：检测 root；有 root 时一键设置 `persist.adb.tcp.port=5555` 并重启 adbd；无 root 时提示改用「无线调试」。
2. Mac 端来源白名单：只接受回环、Tailscale（100.64/10、fd7a:115c:a1e0::/48）与 RFC1918 私有网段，其余一律 403。

待设备可达后做：

3. Mac 端「连接手机」按钮：自动 `adb connect`（Tailscale 地址），失败时提示配对。
4. 无线调试配对引导页（把 IP:端口、六位码的获取步骤内置到 App）。

## 未来功能（已记录，暂不实现）

- **文件访问 / 系统能力**：优先走 OPPO/ColorOS 原生 API 与 SAF，
  不为文件访问引入 Accessibility（Accessibility 适合 UI 自动化，不适合读文件）。
- **每日相册 / 录音巡检**：定时扫描新照片、录音并按规则归档或摘要。
- **Personal Wiki 写入**：生成 entry / observation，写入个人 wiki（需先定 schema 与去重规则）。
- **短信验证码读取**：优先 NotificationListenerService（读验证码通知）；
  `READ_SMS` 属敏感权限，仅在确有需要时申请。
- **UI 自动化**：AccessibilityService 实现点击/读取界面，需明确开关与可见提示。

## 安全要求（所有新功能必须满足）

- 只允许经 Tailscale（或显式配置的私有网络）访问；默认拒绝公网。
- 现有鉴权：`X-PhoneClip-Token`；后续可加「仅接受 100.64/10 与 localhost」的来源过滤。
- 文件路径限制在 `/storage/emulated/0`，跳过 `Android/data`、`Android/obb`。
- 不把文件内容、剪贴板内容写入日志；日志只记录方法与状态。
- Accessibility / 短信 / 录音等敏感能力必须有独立开关、明确提示、可随时关闭。
