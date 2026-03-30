package libtailscale

import (
	"os"
	"sync"

	"github.com/LiuTangLei/wireguard-go/tun"
)

const defaultMTU = 1280

// pendingTUN implements tun.Device for the iOS backend. Initially it acts as
// a placeholder: reads block until the device is closed, and writes are
// discarded. When NEPacketTunnelProvider supplies a real utun file descriptor,
// it can be wired in via a future SetDevice method.
type pendingTUN struct {
	events chan tun.Event
	closed chan struct{}
	once   sync.Once
}

func newPendingTUN() *pendingTUN {
	t := &pendingTUN{
		events: make(chan tun.Event, 1),
		closed: make(chan struct{}),
	}
	t.events <- tun.EventUp
	return t
}

func (t *pendingTUN) File() *os.File { return nil }

func (t *pendingTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	<-t.closed
	return 0, os.ErrClosed
}

func (t *pendingTUN) Write(bufs [][]byte, offset int) (int, error) {
	select {
	case <-t.closed:
		return 0, os.ErrClosed
	default:
		return len(bufs), nil
	}
}

func (t *pendingTUN) MTU() (int, error) { return defaultMTU, nil }

func (t *pendingTUN) Name() (string, error) { return "utun_ts", nil }

func (t *pendingTUN) Events() <-chan tun.Event { return t.events }

func (t *pendingTUN) Close() error {
	t.once.Do(func() { close(t.closed) })
	return nil
}

func (t *pendingTUN) BatchSize() int { return 1 }
