package lib

import (
	"errors"
	"sync"

	"github.com/xtls/xray-core/common/cmdarg"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	coreMu         sync.Mutex
	coreServer     *core.Instance
	coreConfigPath string
)

var ErrAlreadyRunning = errors.New("xray core already running")

func Server(config string) (*core.Instance, error) {
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
//
// Methodology:
//   - It is concurrency-safe.
//   - It is idempotent when called with the same config while already running.
//   - If called while already running with a different config, it performs a safe restart.
//   - If startup fails after instance creation, it closes the instance and leaves the global state stopped.
func Start(dir string, config string) (err error) {
	coreMu.Lock()
	defer coreMu.Unlock()

	SetEnv(dir)

	// Already running with the same config => no-op.
	if coreServer != nil && coreConfigPath == config {
		return nil
	}

	// If running with a different config, stop current instance first (serialized under lock).
	if coreServer != nil {
		_ = coreServer.Close()
		coreServer = nil
		coreConfigPath = ""
	}

	srv, err := Server(config)
	if err != nil {
		return err
	}

	if err = srv.Start(); err != nil {
		_ = srv.Close()
		return err
	}

	coreServer = srv
	coreConfigPath = config
	return nil
}

// Restart stops any running instance (if present) and starts a new one with the provided config.
// It is safe to call even if the core isn't running.
func Restart(dir string, config string) error {
	// Start now implements safe restart semantics when config differs.
	return Start(dir, config)
}

// Stop is a safe "disconnect" operation.
// It is concurrency-safe and idempotent.
func Stop() error {
	coreMu.Lock()
	defer coreMu.Unlock()

	if coreServer == nil {
		return nil
	}

	err := coreServer.Close()
	coreServer = nil
	coreConfigPath = ""
	return err
}

// IsRunning reports whether the core is currently started.
func IsRunning() bool {
	coreMu.Lock()
	defer coreMu.Unlock()
	return coreServer != nil
}

func Version() string {
	return core.Version()
}
