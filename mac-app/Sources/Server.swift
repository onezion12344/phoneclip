import Foundation
import Network

struct PhoneClipConfig: Codable {
    var token: String
    var port: Int
    var listen_ip: String?
    var notify: Bool?
    var sound: Bool?
}

struct HTTPRequest {
    let method: String
    let target: String
    let headers: [String: String]
    let body: Data

    var path: String {
        target.split(separator: "?", maxSplits: 1).first.map(String.init) ?? target
    }

    var query: [String: String] {
        var out: [String: String] = [:]
        guard let q = target.split(separator: "?", maxSplits: 1).dropFirst().first else { return out }
        for pair in q.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            if kv.count == 2, let v = String(kv[1]).removingPercentEncoding {
                out[String(kv[0])] = v
            } else if kv.count == 1 {
                out[String(kv[0])] = ""
            }
        }
        return out
    }
}

enum HTTPParser {
    static func parse(_ data: Data) -> HTTPRequest? {
        guard let sep = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let head = String(decoding: data[..<sep.lowerBound], as: UTF8.self)
        var lines = head.components(separatedBy: "\r\n")
        guard !lines.isEmpty else { return nil }
        let first = lines.removeFirst().split(separator: " ")
        guard first.count >= 2 else { return nil }

        var headers: [String: String] = [:]
        for line in lines {
            guard let idx = line.firstIndex(of: ":") else { continue }
            let key = line[..<idx].lowercased()
            let val = line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
            headers[key] = val
        }

        let rest = data[sep.upperBound...]
        if let te = headers["transfer-encoding"], te.lowercased().contains("chunked") {
            guard let body = decodeChunked(Data(rest)) else { return nil }
            return HTTPRequest(method: String(first[0]), target: String(first[1]),
                               headers: headers, body: body)
        }
        let len = Int(headers["content-length"] ?? "0") ?? 0
        guard rest.count >= len else { return nil }
        return HTTPRequest(method: String(first[0]), target: String(first[1]),
                           headers: headers, body: Data(rest.prefix(len)))
    }

    private static func decodeChunked(_ data: Data) -> Data? {
        var out = Data()
        var i = data.startIndex
        while true {
            guard let lineEnd = data[i...].firstIndex(of: 0x0A) else { return nil }
            var line = String(decoding: data[i..<lineEnd], as: UTF8.self)
            line = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let size = Int(line, radix: 16) else { return nil }
            i = data.index(after: lineEnd)
            if size == 0 { return out }
            guard data.distance(from: i, to: data.endIndex) >= size else { return nil }
            let end = data.index(i, offsetBy: size)
            out.append(data[i..<end])
            i = end
            if i < data.endIndex && data[i] == 0x0D { i = data.index(after: i) }
            if i < data.endIndex && data[i] == 0x0A { i = data.index(after: i) }
        }
    }
}

final class PhoneClipServer {
    let rootDir: String
    let photosDir: String
    let filesDir: String
    let cacheDir: String
    let bridge = ClipboardBridge()

    private let queue = DispatchQueue(label: "ai.onezion.phoneclip.server")
    private var listener: NWListener?
    private var timer: DispatchSourceTimer?
    private(set) var config = PhoneClipConfig(token: "", port: 47411)
    private(set) var running = false
    private(set) var lastError = ""

    private var rev: Int64 = 0
    private var kind = ""
    private var text = ""
    private var imagePath = ""
    private var ignoreCount = 0
    private var lastCount = -1
    private var lastSig = ""

    private struct Batch {
        var paths: [String?]
        var total: Int
        var got: Int
    }
    private var batches: [String: Batch] = [:]

    private struct Waiter {
        let conn: NWConnection
        let since: Int64
        let timeout: DispatchWorkItem?
    }
    private var waiters: [UUID: Waiter] = [:]

    var onStateChange: (() -> Void)?

    init(rootDir: String, portOverride: Int? = nil) {
        self.rootDir = rootDir
        self.photosDir = (rootDir as NSString).deletingLastPathComponent
        self.filesDir = NSHomeDirectory() + "/Downloads/PhoneClip"
        self.cacheDir = rootDir + "/cache"
        for d in [rootDir, cacheDir, photosDir, filesDir,
                  NSHomeDirectory() + "/Pictures/PhoneClip"] {
            try? FileManager.default.createDirectory(atPath: d, withIntermediateDirectories: true)
        }
        loadConfig()
        if let p = portOverride, p > 0 {
            config.port = p
            saveConfig()
        }
    }

    // MARK: config

    private var configPath: String { rootDir + "/config.json" }

    private func loadConfig() {
        if let data = FileManager.default.contents(atPath: configPath),
           let c = try? JSONDecoder().decode(PhoneClipConfig.self, from: data) {
            config = c
        } else {
            let token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            config = PhoneClipConfig(token: token, port: 47411,
                                     listen_ip: nil, notify: true, sound: false)
        }
        if config.token.isEmpty {
            config.token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        }
        saveConfig()
    }

    func saveConfig() {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? enc.encode(config) {
            try? data.write(to: URL(fileURLWithPath: configPath))
        }
    }

    func update(port: Int, token: String) {
        config.port = port
        config.token = token
        saveConfig()
        stop()
        start()
        notifyState()
    }

    func addressList() -> [String] {
        var addrs: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return addrs }
        defer { freeifaddrs(ifaddr) }
        var ptr = first
        while true {
            let ifa = ptr.pointee
            if ifa.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(ifa.ifa_addr, socklen_t(ifa.ifa_addr.pointee.sa_len),
                               &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: host)
                    if ip != "127.0.0.1" && !ip.hasPrefix("169.254") {
                        addrs.append(ip)
                    }
                }
            }
            guard let next = ifa.ifa_next else { break }
            ptr = next
        }
        return addrs
    }

    func primaryAddress() -> String {
        let list = addressList()
        if let ts = list.first(where: { $0.hasPrefix("100.") }) { return ts }
        return list.first ?? "127.0.0.1"
    }

    // MARK: lifecycle

    func start() {
        stop()
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let params = NWParameters.tcp
                params.allowLocalEndpointReuse = true
                let port = NWEndpoint.Port(rawValue: UInt16(self.config.port)) ?? 47411
                let listener = try NWListener(using: params, on: port)
                listener.newConnectionHandler = { [weak self] conn in
                    self?.accept(conn)
                }
                listener.stateUpdateHandler = { [weak self] state in
                    guard let self else { return }
                    if case .failed(let error) = state {
                        self.lastError = "端口 \(self.config.port) 无法监听：\(error.localizedDescription)"
                        self.running = false
                        self.notifyState()
                    }
                }
                listener.start(queue: self.queue)
                self.listener = listener
                self.running = true
                self.startWatch()
                self.notifyState()
            } catch {
                self.lastError = error.localizedDescription
                self.running = false
                self.notifyState()
            }
        }
    }

    func stop() {
        queue.sync {
            listener?.cancel()
            listener = nil
            timer?.cancel()
            timer = nil
            running = false
            for (id, w) in Array(waiters) {
                w.timeout?.cancel()
                respond(w.conn, status: 204, body: Data())
                waiters[id] = nil
            }
        }
    }

    private func notifyState() {
        DispatchQueue.main.async { [weak self] in self?.onStateChange?() }
    }

    // MARK: clipboard watch

    private func startWatch() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.5, repeating: .milliseconds(900))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    private func tick() {
        let count = DispatchQueue.main.sync { bridge.count() }
        if count == lastCount { return }
        lastCount = count
        if count == ignoreCount { return }
        let m = DispatchQueue.main.sync { bridge.read(pngPath: cacheDir + "/macclip.png") }
        let sig = ClipboardBridge.signature(m)
        if sig.isEmpty || sig == lastSig { return }
        lastSig = sig
        rev += 1
        kind = m.kind
        text = m.text
        imagePath = m.pngPath
        signalWaiters()
    }

    private func noteOwnWrite(kind: String, text: String, image: String) {
        self.kind = kind
        self.text = text
        self.imagePath = image
    }

    private func signalWaiters() {
        for (id, w) in Array(waiters) where w.since < rev {
            w.timeout?.cancel()
            waiters[id] = nil
            respondState(w.conn)
        }
    }

    // MARK: HTTP

    private func accept(_ conn: NWConnection) {
        conn.start(queue: queue)
        read(conn, buffer: Data())
    }

    private func read(_ conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, complete, error in
            guard let self else { return }
            var buf = buffer
            if let data { buf.append(data) }
            if let req = HTTPParser.parse(buf) {
                self.dispatch(req, conn)
                return
            }
            if complete || error != nil {
                conn.cancel()
                return
            }
            self.read(conn, buffer: buf)
        }
    }

    private func authorized(_ req: HTTPRequest) -> Bool {
        let h = req.headers["x-phoneclip-token"] ?? ""
        let q = req.query["token"] ?? ""
        return h == config.token || q == config.token
    }

    private func dispatch(_ req: HTTPRequest, _ conn: NWConnection) {
        guard authorized(req) else {
            respond(conn, status: 403, body: Data("forbidden".utf8))
            return
        }
        switch (req.method, req.path) {
        case ("GET", "/v1/ping"):
            respondJSON(conn, ["ok": true])
        case ("POST", "/v1/photo"):
            handlePhoto(req, conn)
        case ("POST", "/v1/file"):
            handleFile(req, conn)
        case ("POST", "/v1/clip"):
            handleClipPost(req, conn)
        case ("GET", "/v1/clip"):
            handleClipGet(req, conn)
        case ("GET", "/v1/clip/image"):
            handleClipImage(conn)
        default:
            respond(conn, status: 404, body: Data())
        }
    }

    private func handlePhoto(_ req: HTTPRequest, _ conn: NWConnection) {
        guard !req.body.isEmpty else { respond(conn, status: 400, body: Data()); return }
        var name = saneName(req.query["name"] ?? "")
        if name.isEmpty { name = "photo.jpg" }
        let day = dateString("yyyy-MM-dd")
        let dir = NSHomeDirectory() + "/Pictures/PhoneClip/" + day
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let path = uniquePath(dir + "/" + dateString("HHmmss") + "-" + name)
        guard (try? req.body.write(to: URL(fileURLWithPath: path))) != nil else {
            respond(conn, status: 500, body: Data()); return
        }
        let count = DispatchQueue.main.sync { bridge.writeImage(path: path) }
        if let count {
            ignoreCount = count
            noteOwnWrite(kind: "image", text: "", image: path)
            Toast.show("照片已从手机复制", "\(name) · \(req.body.count / 1024) KB")
            respond(conn, status: 200, body: Data("ok".utf8))
        } else {
            respond(conn, status: 500, body: Data("clipboard".utf8))
        }
    }

    private func handleFile(_ req: HTTPRequest, _ conn: NWConnection) {
        guard !req.body.isEmpty else { respond(conn, status: 400, body: Data()); return }
        var name = saneName(req.query["name"] ?? "")
        if name.isEmpty { name = "file" }
        let path = uniquePath(filesDir + "/" + name)
        guard (try? req.body.write(to: URL(fileURLWithPath: path))) != nil else {
            respond(conn, status: 500, body: Data()); return
        }

        let batch = req.query["batch"] ?? ""
        let total = Int(req.query["n"] ?? "0") ?? 0
        let idx = Int(req.query["i"] ?? "0") ?? 0
        if batch.isEmpty || total <= 1 {
            if let count = DispatchQueue.main.sync { bridge.writeFiles([path]) } {
                ignoreCount = count
                noteOwnWrite(kind: "file", text: "", image: "")
                Toast.show("文件已从手机复制", name)
            }
            respond(conn, status: 200, body: Data("ok".utf8))
            return
        }

        var b = batches[batch] ?? Batch(paths: Array(repeating: nil, count: max(total, idx + 1)),
                                        total: total, got: 0)
        while b.paths.count <= idx { b.paths.append(nil) }
        b.paths[idx] = path
        b.got += 1
        if b.got >= b.total {
            let paths = b.paths.compactMap { $0 }
            batches[batch] = nil
            if let count = DispatchQueue.main.sync { bridge.writeFiles(paths) } {
                ignoreCount = count
                noteOwnWrite(kind: "file", text: "", image: "")
                Toast.show("文件已从手机复制", "\(paths.count) 个")
            }
        } else {
            batches[batch] = b
        }
        respond(conn, status: 200, body: Data("ok".utf8))
    }

    private func handleClipPost(_ req: HTTPRequest, _ conn: NWConnection) {
        switch req.query["kind"] ?? "" {
        case "text":
            let s = String(decoding: req.body, as: UTF8.self)
            let count = DispatchQueue.main.sync { bridge.writeText(s) }
            ignoreCount = count
            noteOwnWrite(kind: "text", text: s, image: "")
            Toast.show("文字已从手机复制", "\(s.count) 字")
            respond(conn, status: 200, body: Data("ok".utf8))
        case "image":
            let tmp = cacheDir + "/fromphone"
            guard (try? req.body.write(to: URL(fileURLWithPath: tmp))) != nil else {
                respond(conn, status: 500, body: Data()); return
            }
            if let count = DispatchQueue.main.sync { bridge.writeImage(path: tmp) } {
                ignoreCount = count
                noteOwnWrite(kind: "image", text: "", image: tmp)
                Toast.show("图片已从手机复制", "\(req.body.count / 1024) KB")
                respond(conn, status: 200, body: Data("ok".utf8))
            } else {
                respond(conn, status: 500, body: Data("clipboard".utf8))
            }
        default:
            respond(conn, status: 400, body: Data())
        }
    }

    private func handleClipGet(_ req: HTTPRequest, _ conn: NWConnection) {
        let since = Int64(req.query["rev"] ?? "-1") ?? -1
        var wait = Int(req.query["wait"] ?? "0") ?? 0
        if wait > 300 { wait = 300 }
        if wait > 0 && since >= rev {
            let id = UUID()
            let timeout = DispatchWorkItem { [weak self] in
                guard let self, let w = self.waiters[id] else { return }
                self.waiters[id] = nil
                self.respond(w.conn, status: 204, body: Data())
            }
            waiters[id] = Waiter(conn: conn, since: since, timeout: timeout)
            queue.asyncAfter(deadline: .now() + .seconds(wait), execute: timeout)
            return
        }
        respondState(conn)
    }

    private func respondState(_ conn: NWConnection) {
        guard kind != "" else {
            respond(conn, status: 204, body: Data())
            return
        }
        var obj: [String: Any] = ["rev": rev, "kind": kind]
        if kind == "text" { obj["text"] = text }
        if kind == "image" { obj["url"] = "/v1/clip/image?rev=\(rev)" }
        respondJSON(conn, obj)
    }

    private func handleClipImage(_ conn: NWConnection) {
        guard !imagePath.isEmpty, let data = FileManager.default.contents(atPath: imagePath) else {
            respond(conn, status: 404, body: Data()); return
        }
        respond(conn, status: 200, contentType: "image/png", body: data)
    }

    private func respondJSON(_ conn: NWConnection, _ obj: [String: Any]) {
        let data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{}".utf8)
        respond(conn, status: 200, contentType: "application/json", body: data)
    }

    private func respond(_ conn: NWConnection, status: Int, contentType: String = "text/plain",
                         body: Data) {
        let reason: String
        switch status {
        case 200: reason = "OK"
        case 204: reason = "No Content"
        case 400: reason = "Bad Request"
        case 403: reason = "Forbidden"
        case 404: reason = "Not Found"
        case 500: reason = "Internal Server Error"
        default: reason = "OK"
        }
        var head = "HTTP/1.1 \(status) \(reason)\r\n"
        if status != 204 {
            head += "Content-Type: \(contentType)\r\nContent-Length: \(body.count)\r\n"
        }
        head += "Connection: close\r\n\r\n"
        var payload = Data(head.utf8)
        if status != 204 { payload.append(body) }
        conn.send(content: payload, completion: .contentProcessed { _ in conn.cancel() })
    }

    // MARK: helpers

    private func saneName(_ raw: String) -> String {
        var s = (raw as NSString).lastPathComponent
        s = s.replacingOccurrences(of: "\0", with: "")
        if s == "." || s == ".." { return "" }
        return s
    }

    private func uniquePath(_ path: String) -> String {
        if !FileManager.default.fileExists(atPath: path) { return path }
        let url = URL(fileURLWithPath: path)
        let ext = url.pathExtension
        let base = url.deletingPathExtension().path
        for i in 1..<1000 {
            let p = ext.isEmpty ? "\(base)-\(i)" : "\(base)-\(i).\(ext)"
            if !FileManager.default.fileExists(atPath: p) { return p }
        }
        return path
    }

    private func dateString(_ format: String) -> String {
        let f = DateFormatter()
        f.dateFormat = format
        return f.string(from: Date())
    }
}
