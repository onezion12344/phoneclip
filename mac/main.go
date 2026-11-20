// phoneclipd — receives photos and clipboard items from the phone over Tailscale
// and puts them on the Mac clipboard (and serves the Mac clipboard back).
package main

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type config struct {
	Token    string `json:"token"`
	Port     int    `json:"port"`
	ListenIP string `json:"listen_ip"`
	Notify   *bool  `json:"notify"`
	Sound    *bool  `json:"sound"`
}

func (c config) notifyOn() bool { return c.Notify == nil || *c.Notify }
func (c config) soundOn() bool  { return c.Sound != nil && *c.Sound }

var (
	cfg       config
	baseDir   string
	helper    string
	photosDir string

	stMu    sync.RWMutex
	stRev   int64
	stKind  string
	stText  string
	stImage string

	lastCount   int64
	ignoreCount int64
	lastSig     string

	waitMu   sync.Mutex
	waitChan = make(chan struct{})

	fileMu   sync.Mutex
	fileDrop = filepath.Join(home(), "Downloads", "PhoneClip")
	batches  = map[string]*fileBatch{}
)

type fileBatch struct {
	paths []string
	total int
	got   int
}

// signalClip wakes every long-poll holding on GET /v1/clip.
func signalClip() {
	waitMu.Lock()
	close(waitChan)
	waitChan = make(chan struct{})
	waitMu.Unlock()
}

type clipRead struct {
	ChangeCount int64  `json:"changeCount"`
	Kind        string `json:"kind"`
	Text        string `json:"text"`
	Png         string `json:"png"`
}

func main() {
	dir := flag.String("dir", defaultDir(), "support directory")
	flag.Parse()
	baseDir = *dir

	os.MkdirAll(filepath.Join(baseDir, "cache"), 0o755)
	photosDir = filepath.Join(home(), "Pictures", "PhoneClip")
	os.MkdirAll(photosDir, 0o755)
	helper = filepath.Join(baseDir, "bin", "phoneclip-clip")

	loadConfig()
	log.Printf("phoneclipd start: port=%d listen=%q photos=%s", cfg.Port, cfg.ListenIP, photosDir)
	go watch()

	for _, addr := range listenAddrs() {
		go func(a string) {
			log.Printf("listening on %s", a)
			srv := &http.Server{Addr: a, Handler: routes()}
			if err := srv.ListenAndServe(); err != nil {
				log.Printf("listen %s: %v", a, err)
			}
		}(addr)
	}
	logURLs()
	select {}
}

func home() string {
	h, err := os.UserHomeDir()
	if err != nil {
		return "/tmp"
	}
	return h
}

func defaultDir() string {
	return filepath.Join(home(), "Library", "Application Support", "PhoneClip")
}

func loadConfig() {
	path := filepath.Join(baseDir, "config.json")
	raw, err := os.ReadFile(path)
	if err == nil {
		json.Unmarshal(raw, &cfg)
	}
	if cfg.Token == "" {
		cfg.Token = strings.TrimSpace(readFileOr(filepath.Join(baseDir, "token"), ""))
	}
	if cfg.Port == 0 {
		cfg.Port = 47411
	}
	if cfg.ListenIP == "" {
		cfg.ListenIP = tailnetIP()
	}
	log.Printf("config: token=%s port=%d ip=%s", mask(cfg.Token), cfg.Port, cfg.ListenIP)
}

func mask(s string) string {
	if len(s) <= 6 {
		return "***"
	}
	return s[:4] + "…" + s[len(s)-4:]
}

func readFileOr(path, def string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return def
	}
	return string(b)
}

// ---- listeners -------------------------------------------------------------

func listenAddrs() []string {
	// 0.0.0.0 so both Tailscale and the plain LAN (the Ace has no Tailscale)
	// work. Everything is token-authenticated.
	return []string{fmt.Sprintf(":%d", cfg.Port)}
}

func logURLs() {
	port := cfg.Port
	log.Printf("url http://127.0.0.1:%d", port)
	ifaces, err := net.Interfaces()
	if err != nil {
		return
	}
	for _, ifc := range ifaces {
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok || ipnet.IP.To4() == nil || ipnet.IP.IsLoopback() {
				continue
			}
			log.Printf("url http://%s:%d", ipnet.IP, port)
		}
	}
}

func tailnetIP() string {
	candidates := []string{
		"/usr/local/bin/tailscale",
		"/opt/homebrew/bin/tailscale",
		"/Applications/Tailscale.app/Contents/MacOS/Tailscale",
	}
	for _, bin := range candidates {
		out, err := exec.Command(bin, "ip", "-4").Output()
		if err != nil {
			continue
		}
		for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			ip := net.ParseIP(strings.TrimSpace(line))
			if ip != nil && ip.To4() != nil && !ip.IsLoopback() {
				return ip.String()
			}
		}
	}
	return "127.0.0.1"
}

// ---- routes ----------------------------------------------------------------

func routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/ping", auth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"ok": true})
	}))
	mux.HandleFunc("/v1/photo", auth(handlePhoto))
	mux.HandleFunc("/v1/file", auth(handleFile))
	mux.HandleFunc("/v1/clip", auth(handleClip))
	mux.HandleFunc("/v1/clip/image", auth(handleClipImage))
	return mux
}

func auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("X-PhoneClip-Token")
		if got == "" {
			got = r.URL.Query().Get("token")
		}
		if got != cfg.Token {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next(w, r)
	}
}

func handlePhoto(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	name := saneName(r.URL.Query().Get("name"))
	if name == "" {
		name = "photo.jpg"
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 25<<20))
	if err != nil || len(body) == 0 {
		http.Error(w, "empty body", http.StatusBadRequest)
		return
	}
	day := time.Now().Format("2006-01-02")
	dir := filepath.Join(photosDir, day)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	path := filepath.Join(dir, time.Now().Format("150405")+"-"+name)
	if err := os.WriteFile(path, body, 0o644); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := setImageClip(path); err != nil {
		log.Printf("photo saved but clipboard failed: %v", err)
		http.Error(w, "clipboard: "+err.Error(), http.StatusInternalServerError)
		return
	}
	noteOwnWrite("image", "", path)
	log.Printf("photo %s (%d KB) → clipboard", path, len(body)/1024)
	notify("照片已从手机复制", fmt.Sprintf("%s · %d KB", name, len(body)/1024))
	w.Write([]byte("ok"))
}

func handleClip(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		kind := r.URL.Query().Get("kind")
		switch kind {
		case "text":
			body, err := io.ReadAll(io.LimitReader(r.Body, 256<<10))
			if err != nil {
				http.Error(w, "read: "+err.Error(), http.StatusBadRequest)
				return
			}
			if err := setTextClip(string(body)); err != nil {
				http.Error(w, "clipboard: "+err.Error(), http.StatusInternalServerError)
				return
			}
			noteOwnWrite("text", string(body), "")
			log.Printf("phone text → clipboard (%d chars)", len(body))
			notify("文字已从手机复制", fmt.Sprintf("%d 字", len([]rune(string(body)))))
		case "image":
			body, err := io.ReadAll(io.LimitReader(r.Body, 25<<20))
			if err != nil || len(body) == 0 {
				http.Error(w, "empty body", http.StatusBadRequest)
				return
			}
			tmp := filepath.Join(baseDir, "cache", "fromphone")
			if err := os.WriteFile(tmp, body, 0o644); err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			if err := setImageClip(tmp); err != nil {
				http.Error(w, "clipboard: "+err.Error(), http.StatusInternalServerError)
				return
			}
			noteOwnWrite("image", "", tmp)
			log.Printf("phone image → clipboard (%d KB)", len(body)/1024)
			notify("图片已从手机复制", fmt.Sprintf("%d KB", len(body)/1024))
		default:
			http.Error(w, "kind must be text or image", http.StatusBadRequest)
			return
		}
		w.Write([]byte("ok"))
	case http.MethodGet:
		since, _ := strconv.ParseInt(r.URL.Query().Get("rev"), 10, 64)
		if wait, _ := strconv.Atoi(r.URL.Query().Get("wait")); wait > 0 {
			if wait > 300 {
				wait = 300
			}
			waitForRev(since, time.Duration(wait)*time.Second)
		}
		stMu.RLock()
		rev, kind, text := stRev, stKind, stText
		stMu.RUnlock()
		if since >= rev || kind == "" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		resp := map[string]any{"rev": rev, "kind": kind}
		if kind == "text" {
			resp["text"] = text
		}
		if kind == "image" {
			resp["url"] = fmt.Sprintf("/v1/clip/image?rev=%d", rev)
		}
		writeJSON(w, resp)
	default:
		http.Error(w, "method", http.StatusMethodNotAllowed)
	}
}

// waitForRev blocks until the clipboard rev passes since or the deadline hits,
// so the phone can hold one cheap request instead of polling every few seconds.
func waitForRev(since int64, d time.Duration) bool {
	deadline := time.NewTimer(d)
	defer deadline.Stop()
	for {
		stMu.RLock()
		rev := stRev
		stMu.RUnlock()
		if rev > since {
			return true
		}
		waitMu.Lock()
		ch := waitChan
		stMu.RLock()
		rev = stRev
		stMu.RUnlock()
		waitMu.Unlock()
		if rev > since {
			return true
		}
		select {
		case <-ch:
		case <-deadline.C:
			return false
		}
	}
}

// ---- files -----------------------------------------------------------------

func handleFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	q := r.URL.Query()
	name := saneName(q.Get("name"))
	if name == "" {
		name = "file"
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 200<<20))
	if err != nil || len(body) == 0 {
		http.Error(w, "empty body", http.StatusBadRequest)
		return
	}
	os.MkdirAll(fileDrop, 0o755)
	path := uniquePath(filepath.Join(fileDrop, name))
	if err := os.WriteFile(path, body, 0o644); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	batch := q.Get("batch")
	total, _ := strconv.Atoi(q.Get("n"))
	idx, _ := strconv.Atoi(q.Get("i"))
	if batch == "" || total <= 1 {
		setFilesClip([]string{path})
		log.Printf("file %s (%d KB) → clipboard", path, len(body)/1024)
		notify("文件已从手机复制", name)
		w.Write([]byte("ok"))
		return
	}

	fileMu.Lock()
	b := batches[batch]
	if b == nil {
		b = &fileBatch{total: total}
		batches[batch] = b
	}
	b.got++
	for len(b.paths) <= idx {
		b.paths = append(b.paths, "")
	}
	b.paths[idx] = path
	complete := b.got >= b.total
	var paths []string
	if complete {
		paths = b.paths
		delete(batches, batch)
	}
	fileMu.Unlock()

	if complete {
		setFilesClip(paths)
		log.Printf("files (%d) → clipboard", len(paths))
		notify("文件已从手机复制", fmt.Sprintf("%d 个", len(paths)))
	}
	w.Write([]byte("ok"))
}

func uniquePath(path string) string {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return path
	}
	ext := filepath.Ext(path)
	base := strings.TrimSuffix(path, ext)
	for i := 1; i < 1000; i++ {
		p := fmt.Sprintf("%s-%d%s", base, i, ext)
		if _, err := os.Stat(p); os.IsNotExist(err) {
			return p
		}
	}
	return path
}

func setFilesClip(paths []string) error {
	args := append([]string{"write-files"}, paths...)
	out, err := exec.Command(helper, args...).Output()
	if err != nil {
		return err
	}
	count, _ := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
	atomic.StoreInt64(&ignoreCount, count)
	noteOwnWrite("file", "", "")
	return nil
}

func handleClipImage(w http.ResponseWriter, r *http.Request) {
	stMu.RLock()
	path := stImage
	stMu.RUnlock()
	if path == "" {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "image/png")
	io.Copy(w, f)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

// noteOwnWrite remembers what the phone just put on the clipboard so a later
// GET does not replay stale content, without bumping the rev the phone knows.
func noteOwnWrite(kind, text, image string) {
	stMu.Lock()
	stKind, stText, stImage = kind, text, image
	stMu.Unlock()
}

// notify shows a floating toast and plays a soft sound. Notification Center is
// avoided on purpose: osascript run from a LaunchAgent has no notification
// permission. Content is never included — clipboard text may hold passwords.
func notify(subtitle, body string) {
	if !cfg.notifyOn() {
		return
	}
	toast := filepath.Join(baseDir, "bin", "phoneclip-toast")
	if err := exec.Command(toast, subtitle, body).Start(); err != nil {
		log.Printf("toast: %v", err)
	}
	if cfg.soundOn() {
		exec.Command("/usr/bin/afplay", "/System/Library/Sounds/Pop.aiff").Start()
	}
}

func saneName(s string) string {
	s = filepath.Base(strings.TrimSpace(s))
	s = strings.ReplaceAll(s, "\x00", "")
	if s == "." || s == ".." {
		return ""
	}
	return s
}

// ---- mac clipboard ---------------------------------------------------------

func watch() {
	ticker := time.NewTicker(900 * time.Millisecond)
	for range ticker.C {
		count, err := helperCount()
		if err != nil {
			continue
		}
		if count == atomic.LoadInt64(&lastCount) {
			continue
		}
		atomic.StoreInt64(&lastCount, count)
		if count == atomic.LoadInt64(&ignoreCount) {
			continue
		}
		res, err := helperRead()
		if err != nil {
			continue
		}
		sig := contentSig(res)
		if sig == lastSig {
			continue
		}
		lastSig = sig
		stMu.Lock()
		stRev++
		stKind, stText, stImage = res.Kind, res.Text, res.Png
		rev := stRev
		stMu.Unlock()
		signalClip()
		if res.Kind != "" {
			log.Printf("mac clipboard → %s (rev %d)", res.Kind, rev)
		}
	}
}

// contentSig lets identical re-copies (some apps rewrite the pasteboard with
// the same data) avoid bumping the rev the phone polls for.
func contentSig(r clipRead) string {
	switch r.Kind {
	case "text":
		sum := sha1.Sum([]byte(r.Text))
		return "t:" + hex.EncodeToString(sum[:])
	case "image":
		f, err := os.Open(r.Png)
		if err != nil {
			return ""
		}
		defer f.Close()
		h := sha1.New()
		if _, err := io.Copy(h, f); err != nil {
			return ""
		}
		return "i:" + hex.EncodeToString(h.Sum(nil))
	}
	return ""
}

func helperCount() (int64, error) {
	out, err := exec.Command(helper, "count").Output()
	if err != nil {
		return 0, err
	}
	return strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
}

func helperRead() (clipRead, error) {
	var res clipRead
	png := filepath.Join(baseDir, "cache", "macclip.png")
	out, err := exec.Command(helper, "read", png).Output()
	if err != nil {
		return res, err
	}
	if err := json.Unmarshal(out, &res); err != nil {
		return res, err
	}
	if res.Kind == "image" && res.Png != "" {
		shrink(res.Png)
	}
	return res, nil
}

func setImageClip(path string) error {
	out, err := exec.Command(helper, "write-image", path).Output()
	if err != nil {
		return err
	}
	count, _ := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
	atomic.StoreInt64(&ignoreCount, count)
	return nil
}

func setTextClip(text string) error {
	cmd := exec.Command(helper, "write-text")
	cmd.Stdin = strings.NewReader(text)
	out, err := cmd.Output()
	if err != nil {
		return err
	}
	count, _ := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
	atomic.StoreInt64(&ignoreCount, count)
	return nil
}

// shrink caps huge clipboard images (a full-res TIFF photo can be 25 MB+).
func shrink(path string) {
	info, err := os.Stat(path)
	if err != nil || info.Size() < (2<<20) {
		return
	}
	tmp := path + ".small.png"
	if err := exec.Command("sips", "-Z", "2560", "--setProperty", "format", "png",
		path, "--out", tmp).Run(); err != nil {
		os.Remove(tmp)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
	}
}
