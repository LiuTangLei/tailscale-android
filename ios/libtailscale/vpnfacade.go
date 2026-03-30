package libtailscale

import (
	"sync"

	"tailscale.com/net/dns"
	"tailscale.com/wgengine/router"
)

var (
	_ router.Router      = (*VPNFacade)(nil)
	_ dns.OSConfigurator = (*VPNFacade)(nil)
)

// VPNFacade implements both router.Router and dns.OSConfigurator.
// When ReconfigureVPN is called by the backend, SetBoth gets called.
type VPNFacade struct {
	SetBoth           func(rcfg *router.Config, dcfg *dns.OSConfig) error
	GetBaseConfigFunc func() (dns.OSConfig, error)
	InitialMTU        uint32

	mu        sync.Mutex
	didSetMTU bool
	rcfg      *router.Config
	dcfg      *dns.OSConfig
}

func (vf *VPNFacade) Up() error {
	return nil
}

func (vf *VPNFacade) Set(rcfg *router.Config) error {
	vf.mu.Lock()
	defer vf.mu.Unlock()
	if vf.rcfg != nil && vf.rcfg.Equal(rcfg) {
		return nil
	}
	if !vf.didSetMTU {
		vf.didSetMTU = true
		rcfg.NewMTU = int(vf.InitialMTU)
	}
	vf.rcfg = rcfg
	return nil
}

func (vf *VPNFacade) UpdateMagicsockPort(_ uint16, _ string) error {
	return nil
}

func (vf *VPNFacade) SetDNS(dcfg dns.OSConfig) error {
	vf.mu.Lock()
	defer vf.mu.Unlock()
	if vf.dcfg != nil && vf.dcfg.Equal(dcfg) {
		return nil
	}
	vf.dcfg = &dcfg
	return nil
}

func (vf *VPNFacade) SupportsSplitDNS() bool {
	return false
}

func (vf *VPNFacade) GetBaseConfig() (dns.OSConfig, error) {
	if vf.GetBaseConfigFunc == nil {
		return dns.OSConfig{}, dns.ErrGetBaseConfigNotSupported
	}
	return vf.GetBaseConfigFunc()
}

func (vf *VPNFacade) Close() error {
	if vf.SetBoth == nil {
		return nil
	}
	return vf.SetBoth(nil, nil)
}

func (vf *VPNFacade) ReconfigureVPN() error {
	vf.mu.Lock()
	defer vf.mu.Unlock()
	return vf.SetBoth(vf.rcfg, vf.dcfg)
}
