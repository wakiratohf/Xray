package lib

import (
	"errors"
	"log"
	"runtime"
	"runtime/debug"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/common/cmdarg"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	coreMu         sync.RWMutex
	coreServer     *core.Instance
	coreConfigPath string
	// Dùng atomic để check nhanh trạng thái mà không cần lock
	isStopping atomic.Bool
)

// Hàm helper để bắt panic (chống crash app)
func safeRun(action func()) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("XRAYVPN-JNI: RECOVERED FROM PANIC: %v", r)
			debug.PrintStack()
		}
	}()
	action()
}

func Server(config string) (*core.Instance, error) {
	log.Printf("Server: loading config path=%s", config)
	file := cmdarg.Arg{config}
	json, err := core.LoadConfig("json", file)
	if err != nil {
		return nil, err
	}
	server, err := core.New(json)
	if err != nil {
		return nil, err
	}
	return server, nil
}

// Start is a safe "connect" operation.
func Start(dir string, config string) (err error) {
	// Bọc trong safeRun để nếu quá trình start bị panic thì không sập app
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Start: Panic recovered: %v", r)
			err = errors.New("panic during start")
		}
	}()

	coreMu.Lock()
	defer coreMu.Unlock()

	// Reset cờ stopping
	isStopping.Store(false)

	log.Printf("Start: entry dir=%s config=%s", dir, config)
	SetEnv(dir)

	if coreServer != nil && coreConfigPath == config {
		return nil
	}

	if coreServer != nil {
		stopInternal() // Gọi hàm stop nội bộ
	}

	srv, err := Server(config)
	if err != nil {
		return err
	}

	if err = srv.Start(); err != nil {
		srv.Close()
		return err
	}

	coreServer = srv
	coreConfigPath = config
	log.Printf("Start: core started successfully")
	return nil
}

// Stop is a safe "disconnect" operation.
func Stop() error {
	coreMu.Lock()
	defer coreMu.Unlock()
	return stopInternal()
}

// Hàm stop nội bộ, giả định là đã có Lock từ bên ngoài
func stopInternal() error {
	if coreServer == nil {
		return nil
	}

	// 1. Đánh dấu đang dừng để chặn các luồng đọc khác
	isStopping.Store(true)

	log.Printf("XRAYVPN-JNI: Stop: Closing instance...")

	// 2. Sử dụng defer recover riêng cho đoạn Close này
	var err error
	func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("XRAYVPN-JNI: Panic during Close: %v", r)
			}
		}()
		err = coreServer.Close()
	}()

	// 3. QUAN TRỌNG: Ngủ một chút để các Goroutine "ma" (như DefaultDispatch)
	// có thời gian nhận tín hiệu đóng và tự hủy trước khi ta set nil.
	// 200ms - 500ms là đủ để tránh race condition.
	time.Sleep(500 * time.Millisecond)

	coreServer = nil
	coreConfigPath = ""

	// 4. Dọn dẹp bộ nhớ
	runtime.GC()
	debug.FreeOSMemory()

	log.Printf("XRAYVPN-JNI: Stop: Cleanup complete.")
	return err
}

// IsRunning reports whether the core is currently started.
func IsRunning() bool {
	// Nếu đang trong quá trình stop, trả về false luôn để UI không cố update
	if isStopping.Load() {
		return false
	}

	coreMu.RLock()
	defer coreMu.RUnlock()
	return coreServer != nil
}

// Bạn cần bọc tất cả các hàm truy cập vào coreServer như thế này
func GetTrafficStats() (int64, int64) {
	if isStopping.Load() {
		return 0, 0
	}

	coreMu.RLock()
	defer coreMu.RUnlock()

	if coreServer == nil {
		return 0, 0
	}

	// Ví dụ logic lấy stats...
	// return up, down
	return 0, 0
}

func Version() string {
	return core.Version()
}