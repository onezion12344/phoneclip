import AppKit

final class PhoneBrowserController: NSObject, NSTableViewDataSource, NSTableViewDelegate {
    private let server: PhoneClipServer
    private var window: NSWindow!
    private var table: NSTableView!
    private var pathLabel: NSTextField!
    private var statusLabel: NSTextField!
    private var entries: [[String: Any]] = []
    private var currentPath = "/sdcard"
    private var firstShow = true

    init(server: PhoneClipServer) {
        self.server = server
        super.init()
        build()
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        if firstShow {
            firstShow = false
            load(currentPath)
        }
    }

    private func build() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 580, height: 440),
                          styleMask: [.titled, .closable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "手机文件"
        window.center()
        window.isReleasedWhenClosed = false
        let v = NSView(frame: NSRect(x: 0, y: 0, width: 580, height: 440))

        pathLabel = NSTextField(labelWithString: "/sdcard")
        pathLabel.frame = NSRect(x: 16, y: 404, width: 440, height: 20)
        pathLabel.lineBreakMode = .byTruncatingMiddle
        v.addSubview(pathLabel)

        let up = NSButton(title: "返回上级", target: self, action: #selector(goUp))
        up.frame = NSRect(x: 466, y: 396, width: 98, height: 28)
        v.addSubview(up)

        let scroll = NSScrollView(frame: NSRect(x: 16, y: 64, width: 548, height: 322))
        scroll.hasVerticalScroller = true
        table = NSTableView()
        table.headerView = nil
        table.rowHeight = 24
        let col = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
        col.width = 520
        table.addTableColumn(col)
        table.dataSource = self
        table.delegate = self
        table.target = self
        table.doubleAction = #selector(openSelected)
        scroll.documentView = table
        v.addSubview(scroll)

        let dl = NSButton(title: "下载选中", target: self, action: #selector(downloadSelected))
        dl.frame = NSRect(x: 16, y: 20, width: 108, height: 30)
        v.addSubview(dl)

        statusLabel = NSTextField(labelWithString: "")
        statusLabel.frame = NSRect(x: 134, y: 26, width: 430, height: 18)
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.lineBreakMode = .byTruncatingMiddle
        v.addSubview(statusLabel)

        window.contentView = v
    }

    private func load(_ path: String) {
        statusLabel.stringValue = "载入中…（需要手机在线）"
        server.listPhone(path: path) { [weak self] res in
            DispatchQueue.main.async {
                guard let self else { return }
                if res["ok"] as? Bool == true {
                    self.currentPath = res["path"] as? String ?? path
                    self.pathLabel.stringValue = self.currentPath
                    self.entries = res["entries"] as? [[String: Any]] ?? []
                    self.table.reloadData()
                    self.statusLabel.stringValue = "\(self.entries.count) 项"
                } else {
                    self.statusLabel.stringValue = "失败：" + (res["error"] as? String ?? "unknown")
                }
            }
        }
    }

    func numberOfRows(in tableView: NSTableView) -> Int { entries.count }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?,
                   row: Int) -> NSView? {
        guard entries.indices.contains(row) else { return nil }
        let e = entries[row]
        let name = e["name"] as? String ?? ""
        let isDir = e["dir"] as? Bool ?? false
        let id = NSUserInterfaceItemIdentifier("cell")
        let cell: NSTableCellView
        if let reused = tableView.makeView(withIdentifier: id, owner: self) as? NSTableCellView {
            cell = reused
        } else {
            cell = NSTableCellView()
            cell.identifier = id
            let t = NSTextField(labelWithString: "")
            t.frame = NSRect(x: 2, y: 2, width: 500, height: 18)
            t.autoresizingMask = [.width]
            cell.addSubview(t)
            cell.textField = t
        }
        cell.textField?.stringValue = (isDir ? "📁  " : "📄  ") + name
        return cell
    }

    private var selectedEntry: [String: Any]? {
        let r = table.selectedRow
        guard r >= 0 && r < entries.count else { return nil }
        return entries[r]
    }

    @objc private func openSelected() {
        guard let e = selectedEntry else { return }
        if e["dir"] as? Bool == true {
            load(currentPath + "/" + (e["name"] as? String ?? ""))
        } else {
            downloadSelected()
        }
    }

    @objc private func downloadSelected() {
        guard let e = selectedEntry, e["dir"] as? Bool != true else { return }
        let name = e["name"] as? String ?? "file"
        statusLabel.stringValue = "下载 \(name)…"
        server.fetchPhone(path: currentPath + "/" + name) { [weak self] res in
            DispatchQueue.main.async {
                if res["ok"] as? Bool == true {
                    self?.statusLabel.stringValue = "已保存到 ~/Downloads/PhoneClip"
                    Toast.show("已下载", name)
                } else {
                    self?.statusLabel.stringValue = "失败：" + (res["error"] as? String ?? "unknown")
                }
            }
        }
    }

    @objc private func goUp() {
        if currentPath.count > "/sdcard".count {
            load((currentPath as NSString).deletingLastPathComponent)
        }
    }
}
