import Foundation
import Vision
import AppKit
import ImageIO

/// Offline photo index built with the models macOS already ships (no downloads, nothing
/// leaves the machine): OCR (zh + en), scene/object classification, face count.
final class AlbumIndex {
    struct Entry: Codable {
        var path: String          // original phone path
        var name: String
        var thumb: String         // cached thumbnail file
        var text: String          // OCR, joined
        var labels: [String]      // classification identifiers
        var faces: Int
        var width: Int
        var height: Int
        var indexedAt: Double
    }

    private let queue = DispatchQueue(label: "album.index", qos: .utility)
    private var entries: [String: Entry] = [:]   // keyed by original path

    private let dir: String
    private let indexFile: String

    init(rootDir: String) {
        dir = rootDir + "/album"
        indexFile = dir + "/index.json"
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        if let d = FileManager.default.contents(atPath: indexFile),
           let list = try? JSONDecoder().decode([Entry].self, from: d) {
            for e in list { entries[e.path] = e }
        }
    }

    var count: Int { queue.sync { entries.count } }

    /// Index one thumbnail. `src` is the photo path on the phone.
    func ingest(thumbPath: String, src: String, completion: ((Entry?) -> Void)? = nil) {
        queue.async {
            guard let entry = Self.analyze(thumbPath: thumbPath, src: src) else {
                completion?(nil); return
            }
            self.entries[entry.path] = entry
            self.save()
            completion?(entry)
        }
    }

    func search(_ q: String, limit: Int = 50) -> [Entry] {
        let needle = q.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return [] }
        let terms = Self.expand(needle)
        return queue.sync {
            var scored: [(Entry, Int)] = []
            for e in entries.values {
                var score = 0
                let hay = (e.text + " " + e.labels.joined(separator: " ") + " " + e.name)
                    .lowercased()
                for t in terms where hay.contains(t) { score += 1 }
                if e.name.lowercased().contains(needle) { score += 2 }
                if score > 0 { scored.append((e, score)) }
            }
            return scored.sorted { $0.1 > $1.1 || ($0.1 == $1.1 && $0.0.indexedAt > $1.0.indexedAt) }
                .prefix(limit).map { $0.0 }
        }
    }

    func all() -> [Entry] { queue.sync { Array(entries.values) } }

    private func save() {
        let list = Array(entries.values)
        if let d = try? JSONEncoder().encode(list) {
            try? d.write(to: URL(fileURLWithPath: indexFile))
        }
    }

    // MARK: vision

    private static func analyze(thumbPath: String, src: String) -> Entry? {
        guard let image = NSImage(contentsOfFile: thumbPath),
              let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
            return nil
        }
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])

        let textReq = VNRecognizeTextRequest()
        textReq.recognitionLevel = .accurate
        textReq.recognitionLanguages = ["zh-Hans", "en-US"]
        textReq.usesLanguageCorrection = true

        let classifyReq = VNClassifyImageRequest()
        let faceReq = VNDetectFaceRectanglesRequest()

        try? handler.perform([textReq, classifyReq, faceReq])

        var text = ""
        if let obs = textReq.results {
            text = obs.compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
        }
        var labels: [String] = []
        if let obs = classifyReq.results {
            labels = obs.filter { $0.confidence > 0.15 }
                .prefix(6).map { $0.identifier.lowercased() }
        }
        let faces = faceReq.results?.count ?? 0

        return Entry(path: src, name: (src as NSString).lastPathComponent,
                     thumb: thumbPath, text: String(text.prefix(2000)),
                     labels: labels, faces: faces,
                     width: Int(cg.width), height: Int(cg.height),
                     indexedAt: Date().timeIntervalSince1970)
    }

    /// Vision classifies in English; map the common Chinese asks onto its labels.
    private static func expand(_ q: String) -> [String] {
        var out = [q]
        let map: [String: [String]] = [
            "海边": ["beach", "sea", "ocean", "coast", "shore", "water"],
            "海": ["beach", "sea", "ocean", "water"],
            "山": ["mountain", "hill", "peak", "landscape"],
            "猫": ["cat", "kitten", "feline"],
            "狗": ["dog", "puppy", "canine"],
            "食物": ["food", "meal", "dish", "cuisine"],
            "吃": ["food", "meal", "restaurant", "dish"],
            "咖啡": ["coffee", "cafe", "espresso", "cup"],
            "电脑": ["computer", "laptop", "screen", "monitor"],
            "屏幕": ["screen", "monitor", "display"],
            "截图": ["screenshot", "screen"],
            "文字": ["text", "document", "paper"],
            "文档": ["document", "paper", "page", "text"],
            "收据": ["receipt", "invoice", "paper"],
            "花": ["flower", "plant", "garden", "bloom"],
            "树": ["tree", "plant", "forest"],
            "天空": ["sky", "cloud", "sunset", "sunrise"],
            "日落": ["sunset", "sunrise", "sky"],
            "人": ["person", "people", "face", "portrait"],
            "自拍": ["selfie", "portrait", "face", "person"],
            "孩子": ["child", "kid", "baby"],
            "车": ["car", "vehicle", "automobile"],
            "建筑": ["building", "architecture", "house"],
            "室内": ["indoor", "room", "interior"],
            "夜": ["night", "dark", "evening"],
            "雪": ["snow", "winter"],
            "沙滩": ["beach", "sand"],
        ]
        for (k, v) in map where q.contains(k) { out.append(contentsOf: v) }
        return out
    }
}
