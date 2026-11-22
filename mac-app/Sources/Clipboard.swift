import AppKit
import CryptoKit
import Foundation

struct ClipMark {
    var kind = ""        // "text" | "image" | "file" | ""
    var text = ""
    var pngPath = ""
}

final class ClipboardBridge {
    private let pb = NSPasteboard.general

    func count() -> Int { pb.changeCount }

    func read(pngPath: String) -> ClipMark {
        var m = ClipMark()
        guard let types = pb.types else { return m }
        if types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) {
            return m
        }
        if types.contains(.fileURL) {
            m.kind = "file"
            return m
        }
        if let data = pngData() {
            do {
                try data.write(to: URL(fileURLWithPath: pngPath))
                m.kind = "image"
                m.pngPath = pngPath
            } catch {
                m.kind = ""
            }
            return m
        }
        if let s = pb.string(forType: .string) {
            m.kind = "text"
            m.text = s
        }
        return m
    }

    private func pngData() -> Data? {
        if let d = pb.data(forType: .png) { return d }
        if let tiff = pb.data(forType: .tiff), let img = NSImage(data: tiff),
           let rep = NSBitmapImageRep(data: img.tiffRepresentation ?? tiff) {
            return rep.representation(using: .png, properties: [:])
        }
        return nil
    }

    @discardableResult
    func writeText(_ s: String) -> Int {
        pb.clearContents()
        pb.setString(s, forType: .string)
        return pb.changeCount
    }

    @discardableResult
    func writeImage(path: String) -> Int? {
        guard let img = NSImage(contentsOfFile: path), let tiff = img.tiffRepresentation else {
            return nil
        }
        var png: Data?
        if let rep = NSBitmapImageRep(data: tiff) {
            png = rep.representation(using: .png, properties: [:])
        }
        pb.clearContents()
        pb.declareTypes([.png, .tiff], owner: nil)
        if let png { pb.setData(png, forType: .png) }
        pb.setData(tiff, forType: .tiff)
        return pb.changeCount
    }

    @discardableResult
    func writeFiles(_ paths: [String]) -> Int? {
        guard !paths.isEmpty else { return nil }
        pb.clearContents()
        pb.writeObjects(paths.map { URL(fileURLWithPath: $0) as NSURL })
        return pb.changeCount
    }

    static func signature(_ m: ClipMark) -> String {
        switch m.kind {
        case "text":
            return "t:" + hash(m.text.data(using: .utf8) ?? Data())
        case "image":
            return "i:" + hash((try? Data(contentsOf: URL(fileURLWithPath: m.pngPath))) ?? Data())
        case "file":
            return "f"
        default:
            return ""
        }
    }

    static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

/// Small floating confirmation card in the top-right corner.
final class Toast {
    private static var current: NSPanel?

    static func show(_ title: String, _ detail: String) {
        DispatchQueue.main.async {
            current?.close()
            guard let screen = NSScreen.main else { return }
            let w: CGFloat = 340, h: CGFloat = 76, margin: CGFloat = 14
            let frame = NSRect(x: screen.visibleFrame.maxX - w - margin,
                               y: screen.visibleFrame.maxY - h - margin,
                               width: w, height: h)
            let panel = NSPanel(contentRect: frame,
                                styleMask: [.borderless, .nonactivatingPanel],
                                backing: .buffered, defer: false)
            panel.level = .statusBar
            panel.isOpaque = false
            panel.backgroundColor = .clear
            panel.hasShadow = true
            panel.ignoresMouseEvents = true
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]

            let effect = NSVisualEffectView(frame: NSRect(origin: .zero, size: frame.size))
            effect.material = .hudWindow
            effect.blendingMode = .behindWindow
            effect.state = .active
            effect.wantsLayer = true
            effect.layer?.cornerRadius = 14
            effect.layer?.masksToBounds = true

            let icon = NSTextField(labelWithString: "📋")
            icon.font = NSFont.systemFont(ofSize: 22)
            icon.frame = NSRect(x: 16, y: 24, width: 32, height: 30)
            effect.addSubview(icon)

            let t = NSTextField(labelWithString: title)
            t.font = NSFont.systemFont(ofSize: 13, weight: .semibold)
            t.textColor = .labelColor
            t.frame = NSRect(x: 54, y: 42, width: w - 70, height: 18)
            effect.addSubview(t)

            let d = NSTextField(labelWithString: detail)
            d.font = NSFont.systemFont(ofSize: 11)
            d.textColor = .secondaryLabelColor
            d.lineBreakMode = .byTruncatingMiddle
            d.frame = NSRect(x: 54, y: 22, width: w - 70, height: 16)
            effect.addSubview(d)

            panel.contentView = effect
            panel.alphaValue = 0
            panel.orderFrontRegardless()
            current = panel

            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.12
                panel.animator().alphaValue = 1
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.7) {
                NSAnimationContext.runAnimationGroup({ ctx in
                    ctx.duration = 0.28
                    panel.animator().alphaValue = 0
                }, completionHandler: {
                    panel.close()
                    if current === panel { current = nil }
                })
            }
        }
    }
}
