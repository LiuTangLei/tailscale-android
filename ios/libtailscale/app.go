package libtailscale

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime/debug"
	"sync"
	"sync/atomic"

	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnauth"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/localapi"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
	"tailscale.com/net/tsdial"
	"tailscale.com/paths"
	"tailscale.com/tsd"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/util/eventbus"
	"tailscale.com/util/syspolicy/rsop"
	"tailscale.com/util/syspolicy/setting"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/netstack"
)

// App is the concrete iOS libtailscale runtime.
type App struct {
	dataDir        string
	directFileRoot string
	appCtx         AppContext

	store             *stateStore
	policyStore       *syspolicyStore
	logIDPublicAtomic atomic.Pointer[logid.PublicID]

	localAPIHandler http.Handler
	backend         *ipnlocal.LocalBackend
	backendState    *backend // prevent GC; logIDPublicAtomic points into this
	ready           sync.WaitGroup
	tunnelConfigMgr tunnelConfigManager
}

type backend struct {
	engine      wgengine.Engine
	backend     *ipnlocal.LocalBackend
	sys         *tsd.System
	tunDev      *pendingTUN
	netMon      *netmon.Monitor
	logIDPublic logid.PublicID
	logger      *logtail.Logger
	bus         *eventbus.Bus
	appCtx      AppContext
}

func start(dataDir, directFileRoot string, hwAttestationPref bool, appCtx AppContext) Application {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in Start %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	initLogging(appCtx)

	if _, exists := os.LookupEnv("XDG_CACHE_HOME"); !exists {
		os.Setenv("XDG_CACHE_HOME", filepath.Join(dataDir, "cache"))
	}
	if _, exists := os.LookupEnv("XDG_CONFIG_HOME"); !exists {
		os.Setenv("XDG_CONFIG_HOME", filepath.Join(dataDir, "config"))
	}
	if _, exists := os.LookupEnv("HOME"); !exists {
		os.Setenv("HOME", dataDir)
	}

	return newApp(dataDir, directFileRoot, hwAttestationPref, appCtx)
}

func newApp(dataDir, directFileRoot string, hwAttestationPref bool, appCtx AppContext) *App {
	a := &App{
		dataDir:        dataDir,
		directFileRoot: directFileRoot,
		appCtx:         appCtx,
	}
	a.ready.Add(2) // 1 for localAPIHandler, 1 for lb.Start

	a.store = newStateStore(appCtx)
	a.policyStore = &syspolicyStore{a: a}
	netmon.RegisterInterfaceGetter(a.getInterfaces)
	rsop.RegisterStore("DeviceHandler", setting.DeviceScope, a.policyStore)

	hwAttestEnabled := appCtx.HardwareAttestationKeySupported() && hwAttestationPref
	if hwAttestEnabled {
		key.RegisterHardwareAttestationKeyFns(
			func() key.HardwareAttestationKey { return emptyHardwareAttestationKey(appCtx) },
			func() (key.HardwareAttestationKey, error) { return createHardwareAttestationKey(appCtx) },
		)
	}

	go func() {
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in runBackend %s: %s", p, debug.Stack())
				panic(p)
			}
		}()
		if err := a.runBackend(context.Background(), hwAttestEnabled); err != nil {
			log.Printf("fatal error: %v", err)
		}
	}()

	return a
}

func (a *App) runBackend(ctx context.Context, hardwareAttestation bool) error {
	paths.AppSharedDir.Store(a.dataDir)
	hostinfo.SetOSVersion(a.osVersion())
	hostinfo.SetPackage(a.appCtx.GetInstallSource())
	hostinfo.SetDeviceModel(a.modelName())

	b, err := a.newBackend(a.dataDir, a.appCtx, a.store)
	if err != nil {
		return err
	}
	a.logIDPublicAtomic.Store(&b.logIDPublic)
	a.backend = b.backend
	a.backendState = b // prevent GC of backend struct while App is alive
	if hardwareAttestation {
		a.backend.SetHardwareAttested()
	}

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
	a.localAPIHandler = h

	a.ready.Done() // localAPIHandler ready

	<-ctx.Done()
	return ctx.Err()
}

func (a *App) newBackend(dataDir string, appCtx AppContext, store *stateStore) (*backend, error) {
	sys := tsd.NewSystem()
	sys.Set(store)

	logf := logger.RusagePrefixLog(log.Printf)
	b := &backend{
		tunDev: newPendingTUN(),
		appCtx: appCtx,
		bus:    sys.Bus.Get(),
	}

	var logID logid.PrivateID
	if err := logID.UnmarshalText([]byte("dead0000dead0000dead0000dead0000dead0000dead0000dead0000dead0000")); err != nil {
		log.Printf("logID.UnmarshalText fallback: %v", err)
	}
	storedLogID, err := store.read(logPrefKey)
	if err != nil || storedLogID == nil {
		newLogID, err := logid.NewPrivateID()
		if err == nil {
			logID = newLogID
			if enc, err := newLogID.MarshalText(); err == nil {
				store.write(logPrefKey, enc)
			}
		}
	} else {
		if err := logID.UnmarshalText(storedLogID); err != nil {
			log.Printf("logID.UnmarshalText stored: %v", err)
		}
	}

	netMon, err := netmon.New(b.bus, logf)
	if err != nil {
		log.Printf("netmon.New: %v", err)
	}
	b.netMon = netMon
	b.setupLogs(dataDir, logID, logf, sys.HealthTracker.Get())

	dialer := new(tsdial.Dialer)
	vf := &VPNFacade{
		SetBoth:           a.tunnelConfigMgr.onConfigUpdate,
		GetBaseConfigFunc: b.getDNSBaseConfig,
	}
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun:            b.tunDev,
		Router:         vf,
		DNS:            vf,
		ReconfigureVPN: vf.ReconfigureVPN,
		Dialer:         dialer,
		SetSubsystem:   sys.Set,
		NetMon:         netMon,
		HealthTracker:  sys.HealthTracker.Get(),
		Metrics:        sys.UserMetricsRegistry(),
		EventBus:       sys.Bus.Get(),
	})
	if err != nil {
		return nil, fmt.Errorf("runBackend: NewUserspaceEngine: %v", err)
	}
	sys.Set(engine)
	b.logIDPublic = logID.Public()

	ns, err := netstack.Create(logf, sys.Tun.Get(), engine, sys.MagicSock.Get(), dialer, sys.DNSManager.Get(), sys.ProxyMapper())
	if err != nil {
		return nil, fmt.Errorf("netstack.Create: %w", err)
	}
	sys.Set(ns)
	ns.ProcessLocalIPs = true
	ns.ProcessSubnets = true
	sys.NetstackRouter.Set(true)
	if w, ok := sys.Tun.GetOK(); ok {
		w.Start()
	}

	lb, err := ipnlocal.NewLocalBackend(logf, logID.Public(), sys, 0)
	if err != nil {
		engine.Close()
		return nil, fmt.Errorf("runBackend: NewLocalBackend: %v", err)
	}
	if err := ns.Start(lb); err != nil {
		return nil, fmt.Errorf("startNetstack: %w", err)
	}
	if b.logger != nil {
		lb.SetLogFlusher(b.logger.StartFlush)
	}
	b.engine = engine
	b.backend = lb
	b.sys = sys

	go func() {
		if err := lb.Start(ipn.Options{}); err != nil {
			log.Printf("Failed to start LocalBackend: %s", err)
			panic(err)
		}
		a.ready.Done() // lb.Start ready
	}()

	return b, nil
}

func (a *App) osVersion() string {
	v, err := a.appCtx.GetOSVersion()
	if err != nil {
		panic(err)
	}
	return v
}

func (a *App) modelName() string {
	m, err := a.appCtx.GetDeviceName()
	if err != nil {
		panic(err)
	}
	return m
}
