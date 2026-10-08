# Tailscale Android Client — AWG / QUIC fork

## 1.104.1 AWG / QUIC release

The [v1.104.1 release](https://github.com/LiuTangLei/tailscale-android/releases/tag/v1.104.1)
uses the official Android 1.104.1 source and the published fork core
`github.com/LiuTangLei/tailscale v1.104.1`, wireguard-go `v0.0.34`, and
quic-go `v0.63.0-tailscale.1.0.20260929072415-cda3ed094749`.
The universal APK covers arm64-v8a, armeabi-v7a, x86 and x86_64, uses the
existing production certificate, and retains the package name for in-place upgrades.

AWG v2/v3 profile generation, import, peer discovery and sync are retained.
Enabling QUIC clears the saved AWG profile after confirmation; applying AWG
selects native mode. Both changes restart the embedded engine while preserving
node identity. Upstream favorites, sharing and key-expiry updates are included.

Release assets include the exact source/dependency manifest, SHA256 sums and
verification results. Emulator tests use an isolated local test network; they
do not claim physical-phone carrier-network acceptance.

Build with Go 1.27.1 and `make release-signed`. Signing credentials come only
from the `JKS_PATH`, `JKS_PASSWORD`, `JKS_ALIAS` and `JKS_KEY_PASSWORD` environment
variables; no private signing material is included in the repository or release.
The following upstream distribution instructions describe the official app.


## Overview

This repository contains the open source Tailscale Android client.

## Using

#### Tailscale Packages

The latest stable release APK can be obtained from the [Tailscale Packages Stable Track](https://pkgs.tailscale.com/stable/#android).

Unstable releases can be obtained from the [Tailscale Packages Unstable Track](https://pkgs.tailscale.com/unstable/#android).

These APKs include all supported platforms and architectures.  For installing compact APKs, Android TV, or if you want automatic updates, visit the [Google Play Store](https://play.google.com/store/apps/details?id=com.tailscale.ipn).

#### Google Playstore

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=com.tailscale.ipn)

Help us test new features and bug-fixes before they ship to all users! A [beta testing track](https://play.google.com/apps/testing/com.tailscale.ipn) is available on the Play Store. 

#### Amazon Appstore

The app can be downloaded from the [Amazon Appstore](https://www.amazon.com/dp/B0D38TRB3N) for Amazon Fire tablets and Fire TV devices.

#### F-Droid

The [F-Droid](https://f-droid.org/packages/com.tailscale.ipn/) project builds the source code in this repository and maintains independently-built APKs. Note that F-Droid builds are not released, updated, or verified by the Tailscale team.

## Preparing a build environment

There are several options for setting up a build environment. The Android Studio
path is the most useful path for longer term development.

In all cases you will need:

- Go runtime
- Android SDK
- Android SDK components (`make androidsdk` will install them)

### Android Studio

1. Install a Go runtime (https://go.dev/dl/).
2. Install Android Studio (https://developer.android.com/studio).
3. Start Android Studio, from the Welcome screen select "More Actions" and "SDK Manager".
4. In the SDK manager, select the "SDK Tools" tab and install the "Android SDK Command-line Tools (latest)".
3. Run `make androidsdk` to install the necessary SDK components.

If you would prefer to avoid Android Studio, you can also install an Android
SDK. The makefile detects common paths, so `sudo apt install android-sdk` is
sufficient on Debian / Ubuntu systems. To use an Android SDK installed in a
non-standard location, set the `ANDROID_SDK_ROOT` environment variable to the
path to the SDK.

If you installed Android Studio the tools may not be in your path. To get the
correct tool path, run `make androidpath` and export the provided path in your
shell.

#### Code Formatting

The ktmft plugin on the default setting should be used to autoformat all Java, Kotlin
and XML files in Android Studio.  Enable "Format on Save".

### Docker

If you wish to avoid installing software on your host system, a Docker based development strategy is available, you can build and start a shell with:

```sh
make docker-shell
```

Several other makefile recipes are available for setting up the proper build environment and running builds.

Note that the docker makefile recipes s will preserve the image and remove container on completion.
If changes are made to the build environment or toolchain, cached docker images may need to be rebuilt.
The docker build image name is parameterized in the makefile and changing it provides a simple means to do this.

### Nix

If you have Nix 2.4 or later installed, a Nix development environment can
be set up with:

```sh
alias nix='nix --extra-experimental-features "nix-command flakes"'
nix develop
```

The flake provides host tools such as Java, `make`, `curl`, and `git`, and
points the build at a repo-local Android SDK in `./android-sdk`. The SDK
directory is ignored by Git and is reused across builds.

On first use, install the Android SDK components:

```sh
make androidsdk
```

Then build normally:

```sh
make tailscale-debug
```

The debug APK is written to `./tailscale-debug.apk`.

For one-shot commands without entering an interactive shell:

```sh
nix develop --command make androidsdk
nix develop --command make tailscale-debug
```

For faster Kotlin-only iteration while avoiding the `gomobile bind` step:

```sh
nix develop --command bash -lc 'cd android && ./gradlew ktfmtCheck compileDebugKotlin'
```

## Building

```sh
make apk
make install
```

## Building a release

Use `make release-signed` with the original signing environment. Set
`VERSION_CODE_BASE` explicitly when reproducing a release; by default it is
minutes since the Unix epoch multiplied by ten. Android TV uses the next integer.
Keep the value higher than the last published APK to preserve update compatibility.
The published manifest records both the exact value and the source commit.

Use Go 1.27.1, Java 21, and the checked-in Gradle wrapper for this release.

## Developing on a Fire Stick TV

On the Fire Stick:

* Settings > My Fire TV > Developer Options > ADB Debugging > ON

Then some useful commands:
```
adb connect 10.2.200.213:5555
adb install -r tailscale-fdroid.apk
adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity
adb shell pm uninstall com.tailscale.ipn
```

## Bugs

Please file any issues about this code or the hosted service on
[the tailscale issue tracker](https://github.com/tailscale/tailscale/issues).

## Contributing

`under_construction.gif`

PRs welcome, but we are still working out our contribution process and
tooling.

We require [Developer Certificate of
Origin](https://en.wikipedia.org/wiki/Developer_Certificate_of_Origin)
`Signed-off-by` lines in commits.

## About Us

We are [Tailscale](https://tailscale.com). See
https://tailscale.com/company for more about us and what we're
building.

WireGuard is a registered trademark of Jason A. Donenfeld.
