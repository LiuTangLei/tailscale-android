package libtailscale

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"sync"

	"tailscale.com/net/dns"
	"tailscale.com/wgengine/router"
)

// TunnelConfig is the JSON-serializable tunnel configuration pushed to Swift.
// It contains everything NEPacketTunnelProvider needs to call
// setTunnelNetworkSettings.
type TunnelConfig struct {
	// Routes
	LocalAddresses []string `json:"localAddresses"` // e.g. ["100.64.0.2/32", "fd7a:115c:a1e0::1/128"]
	Routes         []string `json:"routes"`          // CIDR routes to include
	ExcludeRoutes  []string `json:"excludeRoutes,omitempty"`

	// DNS
	DNSServers []string `json:"dnsServers"`
	DNSDomains []string `json:"dnsDomains"`

	// Tunnel
	MTU int `json:"mtu"`
}

// tunnelConfigManager holds the callback to Swift and serializes config updates.
type tunnelConfigManager struct {
	mu sync.Mutex
	cb TunnelConfigCallback
}

func (m *tunnelConfigManager) setCallback(cb TunnelConfigCallback) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.cb = cb
}

func (m *tunnelConfigManager) onConfigUpdate(rcfg *router.Config, dcfg *dns.OSConfig) error {
	m.mu.Lock()
	cb := m.cb
	m.mu.Unlock()

	if cb == nil {
		return nil // No callback registered yet
	}

	if rcfg == nil {
		// Tunnel being torn down
		return nil
	}

	tc := TunnelConfig{
		MTU: rcfg.NewMTU,
	}
	if tc.MTU <= 0 {
		tc.MTU = defaultMTU
	}

	// Local addresses
	for _, addr := range rcfg.LocalAddrs {
		tc.LocalAddresses = append(tc.LocalAddresses, addr.String())
	}

	// Routes
	for _, route := range rcfg.Routes {
		tc.Routes = append(tc.Routes, route.String())
	}

	// Excluded routes (SubnetRoutes that shouldn't go through tunnel)
	// On iOS we generally route everything through the tunnel, but we
	// still pass exclude routes for completeness.
	// Nothing to exclude for MVP.

	// DNS
	if dcfg != nil {
		for _, ns := range dcfg.Nameservers {
			tc.DNSServers = append(tc.DNSServers, ns.String())
		}
		for _, d := range dcfg.SearchDomains {
			tc.DNSDomains = append(tc.DNSDomains, d.WithoutTrailingDot())
		}
	}

	// Fallback: if no DNS servers from Go, use MagicDNS default
	if len(tc.DNSServers) == 0 {
		tc.DNSServers = []string{"100.100.100.100"}
	}

	// Fallback: if no routes, route the Tailscale CGNAT range
	if len(tc.Routes) == 0 {
		tc.Routes = []string{
			netip.MustParsePrefix("100.64.0.0/10").String(),
			netip.MustParsePrefix("fd7a:115c:a1e0::/48").String(),
		}
	}

	configJSON, err := json.Marshal(tc)
	if err != nil {
		return fmt.Errorf("marshal tunnel config: %w", err)
	}

	return cb.OnTunnelConfigUpdate(configJSON)
}

// setTunnelConfigCallback is called from the exported SetTunnelConfigCallback.
func (a *App) setTunnelConfigCallback(cb TunnelConfigCallback) {
	a.tunnelConfigMgr.setCallback(cb)
}
