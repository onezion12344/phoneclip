// phoneclip-clip — tiny NSPasteboard helper.
//   phoneclip-clip count
//   phoneclip-clip read <out.png>   -> JSON {changeCount, kind, text, png}
//   phoneclip-clip write-text       <- stdin
//   phoneclip-clip write-image <path>
import AppKit
import Foundation

func emit(_ obj: [String: Any]) {
    if let data = try? JSONSerialization.data(withJSONObject: obj),
       let s = String(data: data, encoding: .utf8) {
        print(s)
    }
}

func pngData(_ pb: NSPasteboard) -> Data? {
    if let d = pb.data(forType: .png) { return d }
    if let tiff = pb.data(forType: .tiff), let img = NSImage(data: tiff) {
        if let rep = NSBitmapImageRep(data: img.tiffRepresentation ?? tiff) {
            return rep.representation(using: .png, properties: [:])
        }
    }
    return nil
}

let args = CommandLine.arguments
let mode = args.count > 1 ? args[1] : ""
let pb = NSPasteboard.general

switch mode {
case "count":
    print(pb.changeCount)

case "read":
    let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    var out: [String: Any] = ["changeCount": pb.changeCount]
    if let types = pb.types, types.contains(concealed) {
        out["kind"] = ""
    } else if let types = pb.types, types.contains(.fileURL) {
        out["kind"] = "file"
    } else if let data = pngData(pb) {
        let path = args.count > 2 ? args[2] : "/tmp/phoneclip_clip.png"
        do {
            try data.write(to: URL(fileURLWithPath: path))
            out["kind"] = "image"
            out["png"] = path
        } catch {
            out["kind"] = ""
        }
    } else if let s = pb.string(forType: .string) {
        out["kind"] = "text"
        out["text"] = s
    } else {
        out["kind"] = ""
    }
    emit(out)

case "write-text":
    let data = FileHandle.standardInput.readDataToEndOfFile()
    let text = String(data: data, encoding: .utf8) ?? ""
    pb.clearContents()
    pb.setString(text, forType: .string)
    print(pb.changeCount)

case "write-image":
    guard args.count > 2,
          let img = NSImage(contentsOfFile: args[2]),
          let tiff = img.tiffRepresentation else { exit(2) }
    var png: Data? = nil
    if let rep = NSBitmapImageRep(data: tiff) {
        png = rep.representation(using: .png, properties: [:])
    }
    pb.clearContents()
    pb.declareTypes([.png, .tiff], owner: nil)
    if let png { pb.setData(png, forType: .png) }
    pb.setData(tiff, forType: .tiff)
    print(pb.changeCount)

case "write-files":
    let urls = args.dropFirst(2).map { URL(fileURLWithPath: $0) as NSURL }
    guard !urls.isEmpty else { exit(2) }
    pb.clearContents()
    pb.writeObjects(urls)
    print(pb.changeCount)

default:
    FileHandle.standardError.write("usage: count | read <png> | write-text | write-image <path>\n".data(using: .utf8)!)
    exit(1)
}
