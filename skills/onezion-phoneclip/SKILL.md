---
name: onezion-phoneclip
description: Operate and extend PhoneClip / OneZion Connect — the owner's Android↔Mac bridge that sends photos, clips, recordings and files over the phone's own HTTP channel (Tailscale/LAN, no cloud, no ADB at runtime). Use when touching the phoneclip repo, building or releasing the APK/DMG, debugging photo sync / sidecar / transcription / 720p compression / daily jobs, or changing the phone UI. Also use when the owner says 手机传照片、剪贴板、侧车、录音同步、照片 observation、OneZion Connect.
---

# OneZion Connect (phoneclip)

Repo: `/Users/onezion12344/orca/projects/ai-foldable/phoneclip`.
Deliverables: APK `ai.onezion.phoneclip` + native macOS menu-bar app (Swift/AppKit, no Electron).
Channel: the phone long-polls the Mac over Tailscale or LAN. No cloud service; the only outbound call is GitHub Releases for OTA.

## When working here

1. Read `AGENTS.md` first — it carries the build commands, architecture and hard constraints measured on this hardware.
2. Run `python3 tests/server_test.py` (16 assertions, includes the real phone HTTP client) and `./tests/logic_test.sh` (9) before claiming anything.
3. If a phone is attached (`adb devices`), run `./selftest.sh <serial>`: it installs, configures, and asserts sidecar list/download, in-app transcription + Mac sync, 720p video, AAC 96k. It also keeps the screen on (Doze otherwise stalls the long-poll) and restores the owner's real settings afterwards.
4. Release only with `./release.sh X.Y.Z` (runs both suites, builds APK + DMG), then push and `gh release create` with **both** assets, then retarget the tag: `gh release edit vX.Y.Z --target $(git rev-parse HEAD)`. A release without `PhoneClip.apk` breaks OTA (`/releases/latest` must always carry the APK).

## Architecture in one screen

- `GET /v1/clip?rev=N&wait=S` long-poll; the response may also carry `cmd {id,op,path}` for sidecar. Phone replies on `POST /v1/sidecar/result?id=`.
- Phone → Mac: `POST /v1/photo`, `POST /v1/file` (`silent=1`, `dir=transcripts`), `POST /v1/clip?kind=text|image`.
- Mac → phone: `GET /v1/clip/image`, commands; Mac config in `~/Library/Application Support/PhoneClip/config.json` (token, port, transcript_dir).
- Mac accepts only loopback / Tailscale 100.64/10 / RFC1918 peers; everything else 403. Token on every call.
- Phone UI: bottom tabs 连接 / 照片 / 录音 / 文件 / 设置. Daily jobs in `ClipService.checkDaily` → `Jobs.photoVlm` (VLM observation, enriched metadata, text-only to Mac) and `Jobs.recorderSync` (audio **and video** under `/sdcard/Recordings`, modes `media` = compress+send, `transcript` = text only).

## Hard-won constraints (do not re-learn)

- Android 13+/ColorOS forbids background clipboard **reads** — phone→Mac clipboard is a tile/manual action, never automatic.
- Transcode must not use an encoder input surface on this Qualcomm device: it produces zero frames until EOS. The pipeline is decoder → YUV `Image` → encoder `COLOR_FormatYUV420Flexible`.
- Test media must be `-pix_fmt yuv420p`; ffmpeg's `testsrc` defaults to yuv444p which the phone decoder rejects.
- Without `READ_MEDIA_AUDIO`, MediaStore shows only app-owned audio — recorder sync scans the directories directly (all-files access is granted).
- A poller can be "ready" while its peer is dead: sidecar commands are re-queued up to 3× before failing.
- Password managers grab API-key fields; keep them plain text with `IMPORTANT_FOR_AUTOFILL_NO`.
- `am start --es key ""` drops the empty string and stores the next flag as the value — the app rejects flag-shaped values.
- The Mac app is ad-hoc signed: `open` fails from sandboxed shells (-10825); the LaunchAgent is the reliable start path.

## Album search for other agents

`GET /v1/album/search?q=海边&limit=40` (token header) returns `{results:[{path,name,text,labels,faces,width,height}]}`.
The index is built on the Mac with system Vision from 512px thumbnails; trigger it with the menu
「索引手机相册（本机 AI）」 or `POST /v1/album/index?path=/sdcard/DCIM/Camera`. Chinese queries are mapped
onto Vision's English labels by a small synonym table in `AlbumIndex.swift`. ColorOS exposes no public
gallery-search API (verified 2026-11), so this local index is the supported path.

## References

- `docs/roadmap.md` — deferred features (SMS via NotificationListenerService, Accessibility automation, OPPO APIs, Personal Wiki writing) with the constraints decided for each.
- `docs/verify-device.md` — device acceptance checklist and failure triage.
- `README.md` — user-facing behaviour and security notes.
