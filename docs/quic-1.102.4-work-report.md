# Android 1.102.4: QUIC integration and release verification

## Implementation

The fork retains package `com.tailscale.ipn` and native AWG v2/v3 configuration.
The Settings screen adds one user-facing **QUIC** choice. QUIC carries native IP
through the existing authenticated HTTP/3 carrier; it is not WireGuard-over-QUIC.
Selecting QUIC clears saved AWG preferences through the core's coordinated API.
Applying or syncing an AWG profile selects native mode and saves the profile.

The Android bridge now loads the managed transport profile before constructing
its Go packet engine, supplies the persistent VarRoot and mode/revision markers,
and serializes complete backend-generation shutdown/reconstruction. Merely
restarting VpnService or a TUN would leave the old engine in place; this code
reconstructs the actual Go backend, refreshes LocalAPI and notification ownership,
releases per-generation resources and restores the Android TUN through the normal
protected/bound-socket path. It does not kill the Android process.

Mode changes are accepted only after active mode equals the requested mode,
desired mode agrees and pending_restart is false. AWG apply also reads back and
compares the saved profile. Configuration conflicts and activation failures are
surfaced, not automatically retried with stale intent. Validation-only requests
remain read-only. There is no fallback to native WG for incompatible peers and
no relaxation of peer authentication, replay protection or ACLs.

## Published dependencies

- Core: `github.com/LiuTangLei/tailscale v1.102.5-0.20260916181858-1f00235ed2ce`,
  exact source `1f00235ed2ceaa2231755a2d2d3677c2d42438c6` (the corrected 1.102.4
  core, not an upstream 1.102.5 upgrade).
- WireGuard fork: `github.com/LiuTangLei/wireguard-go v0.0.32`.
- QUIC fork: `github.com/LiuTangLei/quic-go v0.62.0-tailscale.4`, explicitly
  replaced in this main module because dependency-module replaces are ignored.
- Go 1.26.6, pinned Tailscale compiler revision in `go.toolchain.rev`.
- `GOWORK=off`; release stamp script requires the immutable checksum-verified
  core dependency and stamps both core and Android source commits.

VersionCode is increased from 297646690 to 298264060. The reported base version
is 1.102.4. Release outputs are rebuilt from a clean committed tree so that they
do not silently reuse an older AAR or report a dirty source revision.

## Tests and controlled runtime evidence

- Gradle `testDebugUnitTest`: passes, including activation contract checks for
  active/desired disagreement, pending restart, and request field encoding.
- Credential-free Android instrumentation on explicitly selected private
  `emulator-5580`: QUIC -> AWG -> QUIC, real engine reconstruction, same node
  identity, actual modes verified, AWG clearing/reloading verified.
- A local desktop QUIC peer forced through a private DERP server is used for
  authenticated TSMP round trips in both QUIC phases.
- The kernel-VPN test additionally starts Android VpnService on this emulator,
  sends 262144-byte downloads and uploads through real TCP sockets over the
  VPN, and checks payload length and SHA-256 after each QUIC activation.
  This test passed. It neither installs on nor changes an attached physical
  phone. Test control, identities and emulator state are isolated.
- The first kernel test hit Android's cleartext HttpURLConnection policy,
  before opening a connection. The test now uses a bounded raw TCP HTTP/1.0
  exchange to its explicit lab peer, without changing production network
  security policy. The subsequent actual VPN payload test passed.
- `go test ./libtailscale` on macOS is not a valid Android execution path:
  Android's `android/log.h` is unavailable to that host build. The bridge is
  compiled by the Android NDK/gomobile build and exercised by instrumentation;
  no host Go-suite pass is claimed.

These tests establish controlled compatibility and switching, not long-duration
field acceptance on every Android device, NAT or WAN path.

## Signing and distribution boundary

The previous public v1.102.2 APK is signed by the existing release certificate:

`ac5b02188583e6cd9385ce3c38bb28536e5598e805fac11ca53e590c34303057`

The current development APK uses the existing local Android Debug certificate:

`aba427405cf35f22bd1436322c1d049f77b47ef0449473fe46834455737843c6`

They are different. The preserved `my-release-key.keystore` was not deleted,
replaced or copied into artifacts. Release-signing passwords/alias were not
configured in the current environment, Gradle properties or GitHub Actions
secrets. No passwords were guessed and no new production key was generated.

Accordingly the development APK is **test-only**, and the optimized release APK
is **unsigned / awaiting the original release signer**. Neither is represented
as an in-place update for existing v1.102.2 installations. Do not uninstall a
working production app merely to install the test build. The stable release is
not replaced until the original signer produces and verifies an update APK.

Artifacts are produced by the existing Makefile and Gradle build. Test builds
must contain no real account credentials or production auth keys. Build and
release manifests record source commits, dependency pins, hashes and signing
status without including private keys, passwords or test node state.
