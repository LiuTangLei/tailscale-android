package libtailscale

import (
	"testing"

	"tailscale.com/ipn"
)

type testAppContext struct {
	store map[string]string
}

func newTestAppContext() *testAppContext {
	return &testAppContext{store: make(map[string]string)}
}

func (f *testAppContext) Log(tag, logLine string)                          {}
func (f *testAppContext) EncryptToPref(key, value string) error            { f.store[key] = value; return nil }
func (f *testAppContext) DecryptFromPref(key string) (string, error)       { return f.store[key], nil }
func (f *testAppContext) GetStateStoreKeysJSON() string                    { return "[]" }
func (f *testAppContext) GetOSVersion() (string, error)                    { return "iOS 18.0", nil }
func (f *testAppContext) GetDeviceName() (string, error)                   { return "iPhone", nil }
func (f *testAppContext) GetInstallSource() string                         { return "appstore" }
func (f *testAppContext) ShouldUseGoogleDNSFallback() bool                 { return false }
func (f *testAppContext) IsChromeOS() (bool, error)                        { return false, nil }
func (f *testAppContext) GetInterfacesAsJson() (string, error)             { return "[]", nil }
func (f *testAppContext) GetPlatformDNSConfig() string                     { return "" }
func (f *testAppContext) GetSyspolicyStringValue(key string) (string, error) {
	return "", nil
}
func (f *testAppContext) GetSyspolicyBooleanValue(key string) (bool, error) {
	return false, nil
}
func (f *testAppContext) GetSyspolicyStringArrayJSONValue(key string) (string, error) {
	return "[]", nil
}
func (f *testAppContext) HardwareAttestationKeySupported() bool            { return false }
func (f *testAppContext) HardwareAttestationKeyCreate() (string, error)    { return "", nil }
func (f *testAppContext) HardwareAttestationKeyRelease(id string) error    { return nil }
func (f *testAppContext) HardwareAttestationKeyPublic(id string) ([]byte, error) {
	return nil, nil
}
func (f *testAppContext) HardwareAttestationKeySign(id string, data []byte) ([]byte, error) {
	return nil, nil
}
func (f *testAppContext) HardwareAttestationKeyLoad(id string) error { return nil }

func TestStateStoreReadWrite(t *testing.T) {
	ctx := newTestAppContext()
	s := newStateStore(ctx)

	err := s.WriteState("testkey", []byte("testvalue"))
	if err != nil {
		t.Fatalf("WriteState: %v", err)
	}

	data, err := s.ReadState("testkey")
	if err != nil {
		t.Fatalf("ReadState: %v", err)
	}
	if string(data) != "testvalue" {
		t.Fatalf("ReadState = %q, want testvalue", data)
	}
}

func TestStateStoreReadMissing(t *testing.T) {
	ctx := newTestAppContext()
	s := newStateStore(ctx)

	_, err := s.ReadState("missing")
	if err != ipn.ErrStateNotExist {
		t.Fatalf("ReadState missing = %v, want ErrStateNotExist", err)
	}
}

func TestPendingTUNMTU(t *testing.T) {
	tun := newPendingTUN()
	defer tun.Close()

	mtu, err := tun.MTU()
	if err != nil {
		t.Fatalf("MTU: %v", err)
	}
	if mtu != defaultMTU {
		t.Fatalf("MTU = %d, want %d", mtu, defaultMTU)
	}
}

func TestPendingTUNName(t *testing.T) {
	tun := newPendingTUN()
	defer tun.Close()

	name, err := tun.Name()
	if err != nil {
		t.Fatalf("Name: %v", err)
	}
	if name != "utun_ts" {
		t.Fatalf("Name = %q, want utun_ts", name)
	}
}

func TestPendingTUNWriteDropsPackets(t *testing.T) {
	tun := newPendingTUN()
	defer tun.Close()

	bufs := [][]byte{make([]byte, 100)}
	n, err := tun.Write(bufs, 0)
	if err != nil {
		t.Fatalf("Write: %v", err)
	}
	if n != 1 {
		t.Fatalf("Write returned %d, want 1", n)
	}
}

func TestAdaptInputStream(t *testing.T) {
	in := &testInputStream{data: []byte("hello world")}
	rc := adaptInputStream(in)
	defer rc.Close()

	buf := make([]byte, 128)
	n, _ := rc.Read(buf)
	if string(buf[:n]) != "hello world" {
		t.Fatalf("Read = %q, want hello world", buf[:n])
	}
}

type testInputStream struct {
	data []byte
	done bool
}

func (s *testInputStream) Read() ([]byte, error) {
	if s.done {
		return nil, nil
	}
	s.done = true
	return s.data, nil
}

func (s *testInputStream) Close() error {
	s.done = true
	return nil
}
