// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
)

func (a *App) publishBackend(backend *ipnlocal.LocalBackend, handler http.Handler, err error) {
	a.backendMu.Lock()
	defer a.backendMu.Unlock()
	a.backend, a.localAPIHandler, a.backendErr = backend, handler, err
	close(a.backendChanged)
	a.backendChanged = make(chan struct{})
}

// A reader either receives one complete generation or waits for the next one.
// It never binds the Java application permanently to an old engine/handler.
func (a *App) backendSnapshot(ctx context.Context) (*ipnlocal.LocalBackend, http.Handler, <-chan struct{}, error) {
	for {
		a.backendMu.Lock()
		backend, handler, changed, err := a.backend, a.localAPIHandler, a.backendChanged, a.backendErr
		a.backendMu.Unlock()
		if err != nil {
			return nil, nil, changed, err
		}
		if backend != nil && handler != nil {
			return backend, handler, changed, nil
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return nil, nil, changed, ctx.Err()
		}
	}
}

func (a *App) restartBackend(ctx context.Context) error {
	done := make(chan error, 1)
	select {
	case a.restartRequests <- done:
	case <-ctx.Done():
		return ctx.Err()
	}
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

type transportResponse struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (r *transportResponse) Header() http.Header            { return r.header }
func (r *transportResponse) WriteHeader(status int)         { r.status = status }
func (r *transportResponse) Write(data []byte) (int, error) { return r.body.Write(data) }
func (r *transportResponse) Flush()                         {}

// The core LocalAPI still authorizes, validates and stores each operation.
// Android owns engine lifetime, so this bridge supplies the missing activation
// step for transport changes and AWG sync. It never kills the process or treats
// a VpnService-only restart as a change of the Go packet engine.
func (a *App) serveMobileLocalAPI(w http.ResponseWriter, r *http.Request) {
	endpoint := strings.TrimPrefix(r.URL.Path, "/localapi/v0/")
	activate := r.Method == http.MethodPost && (endpoint == "packet-transport" || endpoint == "awg-sync-apply")
	if activate && endpoint == "packet-transport" && r.Body != nil {
		data, err := io.ReadAll(io.LimitReader(r.Body, (1<<20)+1))
		if err != nil || len(data) > 1<<20 {
			http.Error(w, "invalid transport request", http.StatusBadRequest)
			return
		}
		r.Body = io.NopCloser(bytes.NewReader(data))
		var request struct {
			Action string `json:"action"`
		}
		if json.Unmarshal(data, &request) == nil && request.Action == "validate" {
			activate = false // validation must remain read-only, even if a mode is pending
		}
	}
	if activate {
		a.transportMu.Lock()
		defer a.transportMu.Unlock()
	}
	backend, handler, _, err := a.backendSnapshot(r.Context())
	if err != nil {
		http.Error(w, "mobile backend unavailable: "+err.Error(), http.StatusServiceUnavailable)
		return
	}
	if !activate {
		handler.ServeHTTP(w, r)
		return
	}
	response := &transportResponse{header: make(http.Header), status: http.StatusOK}
	handler.ServeHTTP(response, r)
	if response.status >= 200 && response.status < 300 {
		status, err := backend.TransportStatus()
		if err == nil && status.PendingRestart {
			mode := status.DesiredMode
			if err = a.restartBackend(r.Context()); err == nil {
				status, err = a.verifyTransportActive(r.Context(), mode)
			}
		}
		if err != nil {
			http.Error(w, "configuration saved but mobile activation failed: "+err.Error(), http.StatusServiceUnavailable)
			return
		}
		if endpoint == "packet-transport" {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(status)
			return
		}
	}
	for key, values := range response.header {
		w.Header()[key] = values
	}
	w.WriteHeader(response.status)
	w.Write(response.body.Bytes())
}

func (a *App) verifyTransportActive(ctx context.Context, mode string) (ipn.TransportControlStatus, error) {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		backend, _, _, err := a.backendSnapshot(ctx)
		if err != nil {
			return ipn.TransportControlStatus{}, err
		}
		status, err := backend.TransportStatus()
		if err == nil && status.ActiveMode == mode && status.DesiredMode == mode && !status.PendingRestart {
			return status, nil
		}
		select {
		case <-ctx.Done():
			return status, fmt.Errorf("active mode verification: %w", ctx.Err())
		case <-ticker.C:
		}
	}
}
