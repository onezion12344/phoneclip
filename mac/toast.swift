// phoneclip-toast — 2s floating panel at the top-right. No notification
// permission needed, so it also works when launched from a launchd agent.
import AppKit
import Foundation

let args = CommandLine.arguments
let title = args.count > 1 ? args[1] : "OneZion Clip"
let detail = args.count > 2 ? args[2] : ""

let app = NSApplication.shared
app.setActivationPolicy(.accessory)

guard let screen = NSScreen.main else { exit(1) }

let w: CGFloat = 340
let h: CGFloat = 76
let margin: CGFloat = 14
let frame = NSRect(x: screen.visibleFrame.maxX - w - margin,
                   y: screen.visibleFrame.maxY - h - margin,
                   width: w, height: h)

let panel = NSPanel(contentRect: frame,
                    styleMask: [.borderless, .nonactivatingPanel],
                    backing: .buffered,
                    defer: false)
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

let titleField = NSTextField(labelWithString: title)
titleField.font = NSFont.systemFont(ofSize: 13, weight: .semibold)
titleField.textColor = .labelColor
titleField.frame = NSRect(x: 54, y: 42, width: w - 70, height: 18)
effect.addSubview(titleField)

let detailField = NSTextField(labelWithString: detail)
detailField.font = NSFont.systemFont(ofSize: 11)
detailField.textColor = .secondaryLabelColor
detailField.lineBreakMode = .byTruncatingMiddle
detailField.frame = NSRect(x: 54, y: 22, width: w - 70, height: 16)
effect.addSubview(detailField)

panel.contentView = effect
panel.alphaValue = 0
panel.orderFrontRegardless()

try? "\(title) | \(detail)".write(toFile: "/tmp/phoneclip-toast.last",
                                   atomically: true, encoding: .utf8)

NSAnimationContext.runAnimationGroup { ctx in
    ctx.duration = 0.12
    panel.animator().alphaValue = 1
}

DispatchQueue.main.asyncAfter(deadline: .now() + 1.7) {
    NSAnimationContext.runAnimationGroup({ ctx in
        ctx.duration = 0.28
        panel.animator().alphaValue = 0
    }, completionHandler: {
        NSApp.terminate(nil)
    })
}

app.run()
