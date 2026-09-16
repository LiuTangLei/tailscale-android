// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"encoding/json"
	"log"

	"tailscale.com/ipn"
)

func (app *App) WatchNotifications(mask int, cb NotificationCallback) NotificationManager {
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		for ctx.Err() == nil {
			backend, _, changed, err := app.backendSnapshot(ctx)
			if err != nil {
				return
			}
			watchCtx, stop := context.WithCancel(ctx)
			done := make(chan struct{})
			go func() {
				defer close(done)
				backend.WatchNotifications(watchCtx, ipn.NotifyWatchOpt(mask), func() {}, func(notify *ipn.Notify) bool {
					data, err := json.Marshal(notify)
					if err != nil {
						log.Printf("notification encoding failed: %v", err)
						return true
					}
					if err := cb.OnNotify(data); err != nil {
						log.Printf("notification callback failed: %v", err)
					}
					return watchCtx.Err() == nil
				})
			}()
			select {
			case <-changed:
			case <-ctx.Done():
			case <-done:
				// A backend shutdown can finish its watcher before its owner
				// publishes the next generation. Avoid a hot resubscribe loop.
				select {
				case <-changed:
				case <-ctx.Done():
				}
			}
			stop()
			<-done
		}
	}()
	return &notificationManager{cancel}
}

type notificationManager struct {
	cancel func()
}

func (nm *notificationManager) Stop() { nm.cancel() }
