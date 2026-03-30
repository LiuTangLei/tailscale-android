package libtailscale

import (
	"context"
	"encoding/json"
	"log"
	"runtime/debug"

	"tailscale.com/ipn"
)

func (app *App) WatchNotifications(mask int, cb NotificationCallback) NotificationManager {
	app.ready.Wait()

	ctx, cancel := context.WithCancel(context.Background())
	go app.backend.WatchNotifications(ctx, ipn.NotifyWatchOpt(mask), func() {}, func(notify *ipn.Notify) bool {
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in WatchNotifications %s: %s", p, debug.Stack())
				panic(p)
			}
		}()

		b, err := json.Marshal(notify)
		if err != nil {
			log.Printf("WatchNotifications: marshal: %s", err)
			return true
		}
		if err := cb.OnNotify(b); err != nil {
			log.Printf("WatchNotifications: OnNotify: %s", err)
			return true
		}
		return true
	})
	return &notificationManager{cancel}
}

type notificationManager struct {
	cancel func()
}

func (nm *notificationManager) Stop() {
	nm.cancel()
}
