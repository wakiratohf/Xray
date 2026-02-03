package lib

import (
	"errors"
	"sync"

	"github.com/xtls/xray-core/common/cmdarg"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

type serverState int

const (
	stateIdle serverState = iota
	stateStarting
	stateRunning
	stateStopping
)

var (
	coreServer *core.Instance
	serverMu   sync.Mutex
	state      serverState = stateIdle
)

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

func Start(dir string, config string) (err error) {
	serverMu.Lock()
	defer serverMu.Unlock()

	// Check current state
	if state == stateStarting {
		return errors.New("server is already starting")
	}
	if state == stateRunning {
		return errors.New("server is already running")
	}
	if state == stateStopping {
		return errors.New("server is currently stopping")
	}

	// Mark as starting
	state = stateStarting
	defer func() {
		if err != nil {
			// Reset state on error
			state = stateIdle
			coreServer = nil
		}
	}()

	SetEnv(dir)
	coreServer, err = Server(config)
	if err != nil {
		return
	}
	if err = coreServer.Start(); err != nil {
		// Clean up on start failure
		if coreServer != nil {
			coreServer.Close()
			coreServer = nil
		}
		return
	}
	
	// Mark as running
	state = stateRunning
	return nil
}

func Stop() error {
	serverMu.Lock()
	defer serverMu.Unlock()

	// Check current state
	if state == stateIdle {
		return nil // Already stopped
	}
	if state == stateStopping {
		return errors.New("server is already stopping")
	}
	if state == stateStarting {
		return errors.New("cannot stop server while it is starting")
	}

	// Mark as stopping
	state = stateStopping
	defer func() {
		// Always reset state to idle after stop attempt
		state = stateIdle
		coreServer = nil
	}()

	if coreServer != nil {
		err := coreServer.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

func Version() string {
	return core.Version()
}
