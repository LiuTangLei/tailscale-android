// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"runtime/debug"
	"sync"
	"sync/atomic"

	"tailscale.com/drive/driveimpl"
	_ "tailscale.com/feature/condregister"
	"tailscale.com/feature/taildrop"
	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnauth"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/localapi"
	"tailscale.com/logtail"
	"tailscale.com/net/dns"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/net/tsdial"
	"tailscale.com/paths"
	"tailscale.com/tsd"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/util/eventbus"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/netstack"
	"tailscale.com/wgengine/router"
	"tailscale.com/wgengine/transportprofile"
)

type App struct {
	dataDir string

	// passes along SAF file information for the taildrop manager
	directFileRoot  string
	shareFileHelper ShareFileHelper

	// appCtx is a global reference to the com.tailscale.ipn.App instance.
	appCtx AppContext

	store             *stateStore
	policyStore       *syspolicyStore
	logIDPublicAtomic atomic.Pointer[logid.PublicID]

	localAPIHandler http.Handler
	backend         *ipnlocal.LocalBackend
	backendMu       sync.Mutex
	backendChanged  chan struct{}
	backendErr      error
	restartRequests chan chan error
	transportMu     sync.Mutex

	// logger is the logtail logger whose uploads follow the user's
	// IsClientLoggingEnabled preference. Populated once runBackend wires
	// up the backend; nil before then.
	logger atomic.Pointer[logtail.Logger]
}

func start(dataDir, directFileRoot string, hwAttestationPref bool, appCtx AppContext) Application {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in Start %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	initLogging(appCtx)
	// Set XDG_CACHE_HOME to make os.UserCacheDir work.
	if _, exists := os.LookupEnv("XDG_CACHE_HOME"); !exists {
		cachePath := filepath.Join(dataDir, "cache")
		os.Setenv("XDG_CACHE_HOME", cachePath)
	}
	// Set XDG_CONFIG_HOME to make os.UserConfigDir work.
	if _, exists := os.LookupEnv("XDG_CONFIG_HOME"); !exists {
		cfgPath := filepath.Join(dataDir, "config")
		os.Setenv("XDG_CONFIG_HOME", cfgPath)
	}
	// Set HOME to make os.UserHomeDir work.
	if _, exists := os.LookupEnv("HOME"); !exists {
		os.Setenv("HOME", dataDir)
	}

	return newApp(dataDir, directFileRoot, hwAttestationPref, appCtx)
}

type backend struct {
	engine     wgengine.Engine
	backend    *ipnlocal.LocalBackend
	netstack   *netstack.Impl
	dialer     *tsdial.Dialer
	sys        *tsd.System
	devices    *multiTUN
	settings   settingsFunc
	lastCfg    *router.Config
	lastDNSCfg *dns.OSConfig
	netMon     *netmon.Monitor

	logIDPublic logid.PublicID
	logger      *logtail.Logger
	stopLogs    func()

	bus     *eventbus.Bus
	started chan error

	// avoidEmptyDNS controls whether to use fallback nameservers
	// when no nameservers are provided by Tailscale.
	avoidEmptyDNS bool

	appCtx AppContext
}

type settingsFunc func(*router.Config, *dns.OSConfig) error

func (a *App) runBackend(ctx context.Context, hardwareAttestation bool) error {
	paths.AppSharedDir.Store(a.dataDir)
	hostinfo.SetOSVersion(a.osVersion())
	hostinfo.SetPackage(a.appCtx.GetInstallSource())
	deviceModel := a.deviceName()
	if a.isChromeOS() {
		deviceModel = "ChromeOS: " + deviceModel
	}
	hostinfo.SetDeviceModel(deviceModel)
	hostinfo.SetHostnameFn(func() (string, error) {
		return a.deviceName(), nil
	})

	var restarted chan error
	for {
		next, err := a.runBackendGeneration(ctx, hardwareAttestation, restarted)
		if err != nil {
			a.publishBackend(nil, nil, err)
			if restarted != nil {
				restarted <- err
			}
			return err
		}
		if next == nil {
			return nil
		}
		restarted = next
	}
}

// Each generation owns its engine, TUN facade, notifications and configuration
// channels. Reconfiguration cannot start a second engine over the same state.
func (a *App) runBackendGeneration(parent context.Context, hardwareAttestation bool, restarted chan error) (chan error, error) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	type configPair struct {
		rcfg *router.Config
		dcfg *dns.OSConfig
	}
	configs := make(chan configPair)
	configErrs := make(chan error)
	b, err := a.newBackend(a.dataDir, a.appCtx, a.store, func(rcfg *router.Config, dcfg *dns.OSConfig) error {
		if rcfg == nil {
			return nil
		}
		select {
		case configs <- configPair{rcfg, dcfg}:
		case <-ctx.Done():
			return ctx.Err()
		}
		select {
		case err := <-configErrs:
			return err
		case <-ctx.Done():
			return ctx.Err()
		}
	})
	if err != nil {
		cancel()
		if b != nil {
			b.shutdown()
		}
		return nil, err
	}
	a.logIDPublicAtomic.Store(&b.logIDPublic)
	a.logger.Store(b.logger)
	if hardwareAttestation {
		b.backend.SetHardwareAttested()
	}
	defer func() {
		a.publishBackend(nil, nil, nil)
		cancel() // unblock routing callbacks before shutting down the engine
		b.shutdown()
	}()

	hc := localapi.HandlerConfig{
		Actor:    ipnauth.Self,
		Backend:  b.backend,
		Logf:     log.Printf,
		LogID:    *a.logIDPublicAtomic.Load(),
		EventBus: b.bus,
	}
	h := localapi.NewHandler(hc)
	h.PermitRead = true
	h.PermitWrite = true
	// Publish the handler only after LocalBackend.Start has completed. The
	// event loop below must meanwhile service its VPN configuration callbacks.

	// Contrary to the documentation for VpnService.Builder.addDnsServer,
	// ChromeOS doesn't fall back to the underlying network nameservers if
	// we don't provide any.
	b.avoidEmptyDNS = a.isChromeOS()

	var (
		cfg   configPair
		state ipn.State
	)

	stateCh := make(chan ipn.State)
	go b.backend.WatchNotifications(ctx, ipn.NotifyInitialPrefs|ipn.NotifyInitialState|ipn.NotifyNoNetMap, func() {}, func(notify *ipn.Notify) bool {
		if notify.State != nil {
			select {
			case stateCh <- *notify.State:
			case <-ctx.Done():
				return false
			}
		}
		return true
	})
	for {
		select {
		case err := <-b.started:
			if err != nil {
				return nil, fmt.Errorf("start mobile backend: %w", err)
			}
			b.started = nil
			a.publishBackend(b.backend, h, nil)
			if restarted != nil {
				restarted <- nil
				restarted = nil
			}
		case request := <-a.restartRequests:
			return request, nil
		case <-ctx.Done():
			return nil, ctx.Err()
		case s := <-stateCh:
			state = s
			if state >= ipn.Starting && vpnService.service != nil && b.isConfigNonNilAndDifferent(cfg.rcfg, cfg.dcfg) {
				// On state change, check if there are router or config changes requiring an update to VPNBuilder
				if err := b.updateTUN(cfg.rcfg, cfg.dcfg); err != nil {
					if errors.Is(err, errMultipleUsers) {
						// TODO: surface error to user
					}
					a.closeVpnService(err, b)
				}
			}
		case c := <-configs:
			cfg = c
			if vpnService.service == nil || !b.isConfigNonNilAndDifferent(cfg.rcfg, cfg.dcfg) {
				configErrs <- nil
				break
			}
			configErrs <- b.updateTUN(cfg.rcfg, cfg.dcfg)
		case s := <-onVPNRequested:
			if vpnService.service != nil && vpnService.service.ID() == s.ID() {
				// Still the same VPN instance, do nothing
				break
			}
			netns.SetAndroidProtectFunc(func(fd int) error {
				if !s.Protect(int32(fd)) {
					// TODO(bradfitz): return an error back up to netns if this fails, once
					// we've had some experience with this and analyzed the logs over a wide
					// range of Android phones. For now we're being paranoid and conservative
					// and do the JNI call to protect best effort, only logging if it fails.
					// The risk of returning an error is that it breaks users on some Android
					// versions even when they're not using exit nodes. I'd rather the
					// relatively few number of exit node users file bug reports if Tailscale
					// doesn't work and then we can look for this log print.
					log.Printf("[unexpected] VpnService.protect(%d) returned false", fd)
				}
				return nil // even on error. see big TODO above.
			})
			netns.SetAndroidBindToNetworkFunc(func(fd int) error {
				if ok := a.appCtx.BindSocketToNetwork(int32(fd)); !ok {
					log.Printf("[unexpected] IPNService.bindSocketToNetwork(%d) returned false", fd)
				}
				return nil
			})
			log.Printf("onVPNRequested: rebind required")
			// TODO(catzkorn): When we start the android application
			// we bind sockets before we have access to the VpnService.protect()
			// function which is needed to avoid routing loops. When we activate
			// the service we get access to the protect, but do not retrospectively
			// protect the sockets already opened, which breaks connectivity.
			// As a temporary fix, we rebind and protect the magicsock.Conn on connect
			// which restores connectivity.
			// See https://github.com/tailscale/corp/issues/13814
			b.backend.DebugRebind()

			vpnService.service = s

			if state >= ipn.Starting && b.isConfigNonNilAndDifferent(cfg.rcfg, cfg.dcfg) {
				if err := b.updateTUN(cfg.rcfg, cfg.dcfg); err != nil {
					a.closeVpnService(err, b)
				}
			}
		case s := <-onDisconnect:
			if vpnService.service != nil && vpnService.service.ID() == s.ID() {
				if b.devices.Down() {
					log.Printf("tunnel brought down on disconnect")
				}
				b.CloseTUNs()
				netns.SetAndroidProtectFunc(nil)
				netns.SetAndroidBindToNetworkFunc(nil)
				vpnService.service = nil
			}
		case i := <-onDNSConfigChanged:
			// TODO (barnstar): Consider using [dns.Manager.RecompileDNSConfig] here.
			// NetworkChanged injects a netmon event that has the side effect
			// regenerating the DNS config but have the means to do
			// that independently of userspace engine network changes which may
			// eliminate some unnecessary work.
			go b.NetworkChanged(i)
		}
	}
}

func (a *App) newBackend(dataDir string, appCtx AppContext, store *stateStore,
	settings settingsFunc) (*backend, error) {

	sys := tsd.NewSystem()
	sys.Set(store)

	if pemData, err := appCtx.GetUserCACertsPEM(); err != nil {
		log.Printf("GetUserCACertsPEM: %v", err)
	} else if len(pemData) > 0 {
		pool, err := x509.SystemCertPool()
		if err != nil {
			log.Printf("x509.SystemCertPool: %v; using empty pool", err)
			pool = x509.NewCertPool()
		}
		if pool.AppendCertsFromPEM(pemData) {
			sys.ExtraRootCAs = pool
			log.Printf("loaded user CA certificates into ExtraRootCAs")
		} else {
			log.Printf("failed to parse any user CA certificates from PEM data")
		}
	}

	logf := logger.Logf(log.Printf)
	b := &backend{
		devices:  newTUNDevices(),
		settings: settings,
		appCtx:   appCtx,
		bus:      sys.Bus.Get(),
		started:  make(chan error, 1),
	}

	transportCfg, transportRevision, transportErr := transportprofile.LoadForStart(dataDir)
	if transportErr != nil {
		return b, fmt.Errorf("packet transport profile: %w", transportErr)
	}

	var logID logid.PrivateID
	logID.UnmarshalText([]byte("dead0000dead0000dead0000dead0000dead0000dead0000dead0000dead0000"))
	storedLogID, err := store.read(logPrefKey)
	// In all failure cases we ignore any errors and continue with the dead value above.
	if err != nil || storedLogID == nil {
		// Read failed or there was no previous log id.
		newLogID, err := logid.NewPrivateID()
		if err == nil {
			logID = newLogID
			enc, err := newLogID.MarshalText()
			if err == nil {
				store.write(logPrefKey, enc)
			}
		}
	} else {
		logID.UnmarshalText([]byte(storedLogID))
	}

	netMon, err := netmon.New(b.bus, logf)
	if err != nil {
		return b, fmt.Errorf("netmon.New: %w", err)
	}
	b.netMon = netMon
	b.setupLogs(dataDir, logID, logf, sys.HealthTracker.Get(), a.isClientLoggingEnabled())
	dialer := new(tsdial.Dialer)
	b.dialer = dialer
	vf := &VPNFacade{
		SetBoth:           b.setCfg,
		GetBaseConfigFunc: b.getDNSBaseConfig,
	}
	transportSource := "default"
	if transportRevision != "0" {
		transportSource = "managed"
	}
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun:               b.devices,
		Router:            vf,
		DNS:               vf,
		ReconfigureVPN:    vf.ReconfigureVPN,
		Dialer:            dialer,
		SetSubsystem:      sys.Set,
		NetMon:            b.netMon,
		HealthTracker:     sys.HealthTracker.Get(),
		Metrics:           sys.UserMetricsRegistry(),
		DriveForLocal:     driveimpl.NewFileSystemForLocal(logf),
		EventBus:          sys.Bus.Get(),
		Transport:         transportCfg,
		TransportSource:   transportSource,
		TransportRevision: transportRevision,
		TransportManaged:  true,
	})
	if err != nil {
		return b, fmt.Errorf("runBackend: NewUserspaceEngine: %v", err)
	}
	b.engine = engine
	sys.Set(engine)
	b.logIDPublic = logID.Public()
	ns, err := netstack.Create(logf, sys.Tun.Get(), engine, sys.MagicSock.Get(), dialer, sys.DNSManager.Get(), sys.ProxyMapper())
	if err != nil {
		return b, fmt.Errorf("netstack.Create: %w", err)
	}
	b.netstack = ns
	sys.Set(ns)
	ns.ProcessLocalIPs = false // let Android kernel handle it; VpnBuilder sets this up
	ns.ProcessSubnets = true   // for Android-being-an-exit-node support
	sys.NetstackRouter.Set(true)
	if w, ok := sys.Tun.GetOK(); ok {
		w.Start()
	}
	lb, err := ipnlocal.NewLocalBackend(logf, logID.Public(), sys, 0)
	if err == nil {
		lb.SetVarRoot(dataDir)
	}
	if err != nil {
		return b, fmt.Errorf("runBackend: NewLocalBackend: %v", err)
	}
	b.backend = lb
	if ext, ok := ipnlocal.GetExt[*taildrop.Extension](lb); ok {
		ext.SetFileOps(newAndroidFileOps(a.shareFileHelper))
	}
	if err := ns.Start(lb); err != nil {
		return b, fmt.Errorf("startNetstack: %w", err)
	}
	if b.logger != nil {
		lb.SetLogFlusher(b.logger.StartFlush)
	}
	b.sys = sys
	go func() { b.started <- lb.Start(ipn.Options{}) }()
	return b, nil
}

// shutdown is called only by the generation owner, after canceling routing
// callbacks. Partial-construction failures use the same cleanup path.
func (b *backend) shutdown() {
	if b.devices != nil {
		b.devices.Down()
		b.CloseTUNs()
	}
	if b.netstack != nil {
		b.netstack.Close()
	}
	if b.backend != nil {
		b.backend.Shutdown()
	} else if b.engine != nil {
		b.engine.Close()
		<-b.engine.Done()
	}
	if b.stopLogs != nil {
		b.stopLogs()
	}
	if b.netMon != nil {
		b.netMon.Close()
	}
	if b.dialer != nil {
		b.dialer.Close()
	}
	if b.bus != nil {
		b.bus.Close()
	}
}

func (a *App) watchFileOpsChanges() {
	for {
		select {
		case helper := <-onShareFileHelper:
			log.Printf("Got ShareFileHelper")
			a.shareFileHelper = helper
		}
	}
}

func (b *backend) isConfigNonNilAndDifferent(rcfg *router.Config, dcfg *dns.OSConfig) bool {
	if reflect.DeepEqual(rcfg, b.lastCfg) && reflect.DeepEqual(dcfg, b.lastDNSCfg) {
		b.logger.Logf("isConfigNonNilAndDifferent: no change to Routes or DNS, ignore")
		return false
	}
	return rcfg != nil
}

func (a *App) closeVpnService(err error, b *backend) {
	log.Printf("VPN update failed: %v", err)

	mp := new(ipn.MaskedPrefs)
	mp.WantRunning = false
	mp.WantRunningSet = true

	if _, localApiErr := a.EditPrefs(*mp); localApiErr != nil {
		log.Printf("localapi edit prefs error %v", localApiErr)
	}

	if b.devices.Down() {
		log.Printf("tunnel brought down on VPN service error: %v", err)
	}
	b.CloseTUNs()

	vpnService.service.DisconnectVPN()
	vpnService.service = nil
}
