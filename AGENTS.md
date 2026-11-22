---
updated: "2026-11-22 12:50"
---

# phoneclip — agent notes

## What this is
Android app + native macOS menu-bar app. Photos and shared files land on the Mac
clipboard over the phone's own HTTP channel (LAN or Tailscale). No cloud service,
no Electron, no ADB at runtime.

## Build / run
| Task | Command |
|---|---|
| APK (no Gradle) | `./build.sh` → `build/PhoneClip.apk` |
| Mac app | `mac-app/build-app.sh` → `mac-app/build/PhoneClip.app` |
| Install Mac app + login agent | `./install-mac.sh` (LaunchAgent `ai.onezion.phoneclip.mac`) |
| Deploy to phone | `./deploy-phone.sh <serial>` (install, permissions, configure, test image) |
| Host logic tests | `./tests/logic_test.sh` (OTA version compare, sha1; JVM, no device) |
| Device self-test | `./selftest.sh <serial>` (sidecar, transcription mock, 720p, AAC); checklist in `docs/verify-device.md` |
| Mac protocol tests | `python3 tests/server_test.py` (14 assertions, private port, two simulated phones) |
| Release | `./release.sh X.Y.Z`, then `gh release create vX.Y.Z build/PhoneClip.apk build/PhoneClip-X.Y.Z.dmg` |

`release.sh` and `install-mac.sh` run the protocol tests and abort on failure
(`SKIP_TESTS=1` bypasses the install gate). Negative control verified: removing the
app binary makes the release exit 1 with the manifest untouched.


## Architecture
- Phone long-polls `GET /v1/clip?rev=N&wait=S`; the Mac answer may also carry a
  `cmd {id,op,path}` for sidecar browsing. Phone replies on `/v1/sidecar/result?id=`.
- Phone → Mac: `POST /v1/photo`, `/v1/file` (`silent=1` = save without clipboard,
  `dir=transcripts` = 偏好设置里的转录文件夹，默认 `~/Documents/PhoneClip/Transcripts`), `POST /v1/clip?kind=text|image`.
- Mac state: `rev` bumps only on real clipboard changes; `noteOwnWrite` updates state
  **without** bumping rev so the phone does not re-apply its own write.
- Shared config: `~/Library/Application Support/PhoneClip/config.json` (token, port).
- Sidecar is confined to `/storage/emulated/0`; `Android/data` and `Android/obb` are hidden.

## Hard constraints (measured, don't re-learn)
- Android 13+ forbids background clipboard **reads**; ColorOS 16 rejects overlay,
  accessibility, legacy targetSdk, and root/shell. Phone → Mac clipboard is the
  Quick Settings tile, never automatic.
- Ace 3 Pro (PJX110) loses adb `5555` on reboot (no root, `persist.adb.tcp.port`
  unwritable). Needs wireless-debugging pairing after a restart.
- The Mac app is ad-hoc signed: `open` from sandboxed shells fails with `-10825`;
  the LaunchAgent is the reliable start path. First manual open may need right-click → Open.
- 「偏好设置 → 启用开机启动」只写/删 LaunchAgent plist，**不** bootstrap/bootout：
  bootout 会杀掉当前进程，bootstrap 会拉起第二个实例。改动下次登录生效。
- OTA updates only install over the same signing key: keep `~/.android/debug.keystore`.
- Full-disk sidecar needs the `MANAGE_EXTERNAL_STORAGE` special access toggle.
- Video compression is MediaCodec H.264 720p (`~2.5 Mbps`); the audio track is copied
  as-is and only when it is AAC (`mp4a`). Audio-only compression re-encodes to AAC 96 kbps.
- The transcription API key lives in the phone's SharedPreferences (plaintext, by design).
  Never log it and never commit it.

## Verification status
- **Mac side verified locally**: HTTP protocol, long-poll latency, sidecar command
  queue + file-browser UI (list → select → download), transcript folder, menu bar,
  LaunchAgent restart, DMG contents.
- **Phone side not yet verified on a device** (both phones were offline on 2026-11-22):
  sidecar list/get, transcription, 720p/AAC compression, redesigned UI and icon.
  Run `./selftest.sh <serial>` or the in-app 「运行自检」 when a device is reachable.

Verification status: the Mac side is covered by `tests/server_test.py` (16 assertions) and
has been run as a shipped artifact (DMG cold start, OTA asset/hash/version). The Android
features added in 1.4 — sidecar, in-app transcription, 720p/AAC compression, Material You UI —
have NOT been executed on a phone yet. Do not describe them as device-tested until
`selftest.sh` has actually passed on hardware.
