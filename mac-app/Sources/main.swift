import AppKit
import Darwin

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var server: PhoneClipServer!
    private var statusItem: NSStatusItem!
    private var prefsWindow: NSWindow?
    private var browser: PhoneBrowserController?
    private var portField: NSTextField!
    private var tokenField: NSTextField!
    private var autostartButton: NSButton!
    private var autostartStatus: NSTextField!
    private var transcriptStatus: NSTextField!

    func applicationDidFinishLaunching(_ notification: Notification) {
        let args = ProcessInfo.processInfo.arguments
        let dir = value(after: "--dir", in: args)
            ?? NSHomeDirectory() + "/Library/Application Support/PhoneClip"
        var portOverride: Int?
        if let p = value(after: "--port", in: args) { portOverride = Int(p) }

        server = PhoneClipServer(rootDir: dir, portOverride: portOverride)
        server.onStateChange = { [weak self] in self?.rebuildMenu() }
        server.start()

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "doc.on.clipboard",
                                   accessibilityDescription: "PhoneClip")
            button.image?.isTemplate = true
            button.toolTip = "PhoneClip"
        }
        rebuildMenu()
    }

    func applicationWillTerminate(_ notification: Notification) {
        server?.stop()
    }

    private func value(after flag: String, in args: [String]) -> String? {
        guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    // MARK: menu

    private func rebuildMenu() {
        guard let statusItem, let server else { return }
        let menu = NSMenu()
        let state = server.running ? "运行中" : "已停止"
        let title = NSMenuItem(title: "PhoneClip · \(state) · :\(server.config.port)",
                               action: nil, keyEquivalent: "")
        title.isEnabled = false
        menu.addItem(title)

        if !server.lastError.isEmpty {
            let err = NSMenuItem(title: server.lastError, action: nil, keyEquivalent: "")
            err.isEnabled = false
            menu.addItem(err)
        }

        menu.addItem(.separator())
        add(menu, "复制服务器地址（\(server.primaryAddress()):\(server.config.port)）",
            #selector(copyAddress))
        add(menu, "复制 Token", #selector(copyToken))
        menu.addItem(.separator())
        add(menu, "打开照片文件夹", #selector(openPhotos))
        add(menu, "打开文件文件夹", #selector(openFiles))
        add(menu, "手机文件…", #selector(openBrowser))
        add(menu, "打开转录文件夹", #selector(openTranscripts))
        menu.addItem(.separator())
        add(menu, "偏好设置…", #selector(openPrefs))
        add(menu, "重启服务", #selector(restart))
        menu.addItem(.separator())
        add(menu, "退出 PhoneClip", #selector(quit))

        statusItem.menu = menu
    }

    private func add(_ menu: NSMenu, _ title: String, _ action: Selector) {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        menu.addItem(item)
    }

    @objc private func copyAddress() {
        copy("http://\(server.primaryAddress()):\(server.config.port)")
    }

    @objc private func copyToken() {
        copy(server.config.token)
    }

    private func copy(_ s: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
        Toast.show("已复制", s)
    }

    @objc private func openPhotos() {
        NSWorkspace.shared.open(URL(fileURLWithPath: NSHomeDirectory() + "/Pictures/PhoneClip"))
    }

    @objc private func openFiles() {
        NSWorkspace.shared.open(URL(fileURLWithPath: NSHomeDirectory() + "/Downloads/PhoneClip"))
    }

    @objc private func openBrowser() {
        if browser == nil { browser = PhoneBrowserController(server: server) }
        browser?.show()
    }

    @objc private func openTranscripts() {
        let dir = NSHomeDirectory() + "/Documents/PhoneClip/Transcripts"
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        NSWorkspace.shared.open(URL(fileURLWithPath: dir))
    }

    @objc private func restart() {
        server.start()
        Toast.show("PhoneClip", "服务已重启")
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    // MARK: preferences

    @objc private func openPrefs() {
        if prefsWindow == nil { buildPrefs() }
        NSApp.activate(ignoringOtherApps: true)
        prefsWindow?.makeKeyAndOrderFront(nil)
    }

    private func buildPrefs() {
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 250),
                         styleMask: [.titled, .closable], backing: .buffered, defer: false)
        w.title = "PhoneClip 设置"
        w.center()
        w.isReleasedWhenClosed = false
        let v = NSView(frame: NSRect(x: 0, y: 0, width: 400, height: 250))

        let portLabel = NSTextField(labelWithString: "端口")
        portLabel.frame = NSRect(x: 24, y: 200, width: 60, height: 20)
        v.addSubview(portLabel)

        portField = NSTextField(frame: NSRect(x: 100, y: 196, width: 270, height: 24))
        portField.stringValue = "\(server.config.port)"
        v.addSubview(portField)

        let tokenLabel = NSTextField(labelWithString: "Token")
        tokenLabel.frame = NSRect(x: 24, y: 160, width: 60, height: 20)
        v.addSubview(tokenLabel)

        tokenField = NSTextField(frame: NSRect(x: 100, y: 156, width: 270, height: 24))
        tokenField.stringValue = server.config.token
        tokenField.font = NSFont.monospacedSystemFont(ofSize: 11, weight: .regular)
        v.addSubview(tokenField)

        let regen = NSButton(title: "重新生成 Token", target: self, action: #selector(regenToken))
        regen.frame = NSRect(x: 100, y: 120, width: 150, height: 26)
        v.addSubview(regen)

        autostartButton = NSButton(title: "启用开机启动", target: self,
                                   action: #selector(toggleAutostart))
        autostartButton.frame = NSRect(x: 24, y: 78, width: 140, height: 28)
        v.addSubview(autostartButton)

        autostartStatus = NSTextField(labelWithString: "")
        autostartStatus.font = NSFont.systemFont(ofSize: 11)
        autostartStatus.textColor = .secondaryLabelColor
        autostartStatus.frame = NSRect(x: 176, y: 84, width: 200, height: 18)
        v.addSubview(autostartStatus)

        let tButton = NSButton(title: "选择转录文件夹…", target: self,
                               action: #selector(chooseTranscriptDir))
        tButton.frame = NSRect(x: 24, y: 46, width: 140, height: 26)
        v.addSubview(tButton)

        transcriptStatus = NSTextField(labelWithString: server.transcriptDir)
        transcriptStatus.font = NSFont.systemFont(ofSize: 10)
        transcriptStatus.textColor = .secondaryLabelColor
        transcriptStatus.lineBreakMode = .byTruncatingMiddle
        transcriptStatus.frame = NSRect(x: 176, y: 50, width: 200, height: 16)
        v.addSubview(transcriptStatus)

        let save = NSButton(title: "保存并重启", target: self, action: #selector(savePrefs))
        save.keyEquivalent = "\r"
        save.frame = NSRect(x: 246, y: 20, width: 124, height: 30)
        v.addSubview(save)

        let cancel = NSButton(title: "取消", target: self, action: #selector(closePrefs))
        cancel.frame = NSRect(x: 166, y: 20, width: 70, height: 30)
        v.addSubview(cancel)

        w.contentView = v
        prefsWindow = w
        updateAutostart()
    }

    private var autostartPath: String {
        NSHomeDirectory() + "/Library/LaunchAgents/ai.onezion.phoneclip.mac.plist"
    }

    private var autostartInstalled: Bool {
        FileManager.default.fileExists(atPath: autostartPath)
    }

    @objc private func toggleAutostart() {
        if autostartInstalled {
            // never bootout: that would terminate this very session
            try? FileManager.default.removeItem(atPath: autostartPath)
            Toast.show("开机自启", "已停用（本次运行继续）")
        } else if let exe = Bundle.main.executablePath {
            let plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>\n"
                + "<key>Label</key><string>ai.onezion.phoneclip.mac</string>\n"
                + "<key>ProgramArguments</key><array><string>\(exe)</string></array>\n"
                + "<key>RunAtLoad</key><true/>\n"
                + "<key>KeepAlive</key><true/>\n"
                + "<key>LimitLoadToSessionType</key><string>Aqua</string>\n"
                + "<key>StandardOutPath</key><string>/tmp/phoneclip-app.log</string>\n"
                + "<key>StandardErrorPath</key><string>/tmp/phoneclip-app.log</string>\n"
                + "</dict></plist>\n"
            do {
                try plist.write(toFile: autostartPath, atomically: true, encoding: .utf8)
                Toast.show("开机自启", "已启用（下次登录生效）")
            } catch {
                Toast.show("开机自启", "写入失败：\(error.localizedDescription)")
            }
        }
        updateAutostart()
    }

    private func updateAutostart() {
        autostartButton?.title = autostartInstalled ? "停用开机启动" : "启用开机启动"
        autostartStatus?.stringValue = autostartInstalled ? "当前：已启用" : "当前：未启用"
    }

    @objc private func chooseTranscriptDir() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.directoryURL = URL(fileURLWithPath: server.transcriptDir)
        panel.prompt = "选择"
        panel.begin { [weak self] response in
            guard let self, response == .OK, let url = panel.url else { return }
            self.server.setTranscriptDir(url.path)
            self.transcriptStatus.stringValue = url.path
            Toast.show("转录文件夹", url.path)
        }
    }

    @objc private func regenToken() {
        tokenField.stringValue = UUID().uuidString
            .replacingOccurrences(of: "-", with: "").lowercased()
    }

    @objc private func savePrefs() {
        var port = Int(portField.stringValue) ?? server.config.port
        if port < 1 || port > 65535 { port = server.config.port }
        let token = tokenField.stringValue.trimmingCharacters(in: .whitespaces)
        if token.isEmpty {
            Toast.show("设置", "Token 不能为空")
            return
        }
        server.update(port: port, token: token)
        closePrefs()
    }

    @objc private func closePrefs() {
        prefsWindow?.orderOut(nil)
    }
}

let allowMulti = CommandLine.arguments.contains("--allow-multi")
if !allowMulti {
    // NSRunningApplication misses processes started by exec/launchd; flock is reliable.
    let lockPath = NSTemporaryDirectory() + "ai.onezion.phoneclip.mac.lock"
    let fd = open(lockPath, O_CREAT | O_RDWR, 0o600)
    if fd < 0 || flock(fd, LOCK_EX | LOCK_NB) != 0 {
        exit(0)
    }
    // fd stays open for the lifetime of the process
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
