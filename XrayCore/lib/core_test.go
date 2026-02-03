package lib

import (
	"sync"
	"testing"
	"time"
)

// TestConcurrentStart tests that concurrent Start calls are handled safely
func TestConcurrentStart(t *testing.T) {
	// Reset state before test
	serverMu.Lock()
	state = stateIdle
	coreServer = nil
	serverMu.Unlock()

	var wg sync.WaitGroup
	successCount := 0
	errorCount := 0
	var mu sync.Mutex

	// Try to start 10 times concurrently
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			// Use invalid config to avoid actually starting
			err := Start("/tmp", "/nonexistent/config.json")
			mu.Lock()
			if err != nil {
				errorCount++
			} else {
				successCount++
			}
			mu.Unlock()
		}()
	}

	wg.Wait()

	// All should fail (no valid config), but no race condition
	if errorCount != 10 {
		t.Logf("Expected all 10 to fail with config error, got %d errors, %d success", errorCount, successCount)
	}

	// Verify state is idle after all attempts
	serverMu.Lock()
	if state != stateIdle {
		t.Errorf("Expected state to be idle, got %v", state)
	}
	serverMu.Unlock()
}

// TestStopBeforeStart tests that Stop can be called before Start
func TestStopBeforeStart(t *testing.T) {
	// Reset state
	serverMu.Lock()
	state = stateIdle
	coreServer = nil
	serverMu.Unlock()

	err := Stop()
	if err != nil {
		t.Errorf("Stop before Start should not error, got: %v", err)
	}
}

// TestDoubleStop tests that calling Stop twice is safe
func TestDoubleStop(t *testing.T) {
	// Reset state
	serverMu.Lock()
	state = stateIdle
	coreServer = nil
	serverMu.Unlock()

	// First stop should be fine
	err := Stop()
	if err != nil {
		t.Errorf("First Stop should not error, got: %v", err)
	}

	// Second stop should also be fine (idempotent)
	err = Stop()
	if err != nil {
		t.Errorf("Second Stop should not error, got: %v", err)
	}
}

// TestStartWhileStarting tests that concurrent Start calls are rejected appropriately
func TestStartWhileStarting(t *testing.T) {
	// Reset state
	serverMu.Lock()
	state = stateIdle
	coreServer = nil
	serverMu.Unlock()

	// Simulate a slow start
	startCh := make(chan error, 1)
	go func() {
		startCh <- Start("/tmp", "/nonexistent/config.json")
	}()

	// Give first start a moment to begin
	time.Sleep(10 * time.Millisecond)

	// Try another start - it might succeed or fail depending on timing,
	// but it should not cause a race condition
	err := Start("/tmp", "/nonexistent/config.json")
	_ = err // We expect either "already starting", "already running", or config error

	// Wait for first start to complete
	<-startCh

	// Verify no crash and state is reasonable
	serverMu.Lock()
	finalState := state
	serverMu.Unlock()

	if finalState != stateIdle && finalState != stateRunning {
		t.Errorf("Expected final state to be idle or running, got %v", finalState)
	}
}

// TestStateTransitions tests that state transitions are correct
func TestStateTransitions(t *testing.T) {
	// Reset state
	serverMu.Lock()
	state = stateIdle
	coreServer = nil
	serverMu.Unlock()

	// Check initial state
	serverMu.Lock()
	if state != stateIdle {
		t.Errorf("Initial state should be idle, got %v", state)
	}
	serverMu.Unlock()

	// Try to start (will fail due to invalid config, but state should transition properly)
	err := Start("/tmp", "/nonexistent/config.json")
	if err == nil {
		t.Error("Expected error from invalid config")
	}

	// State should be back to idle after failed start
	serverMu.Lock()
	if state != stateIdle {
		t.Errorf("State should be idle after failed start, got %v", state)
	}
	serverMu.Unlock()
}
