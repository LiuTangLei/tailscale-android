# Tailscale Android Client — AWG / QUIC fork

## 1.104.4 release

[Download the universal APK](https://github.com/LiuTangLei/tailscale-android/releases/download/v1.104.4/tailscale-release-signed.apk).

Updated to Tailscale 1.104.4 with AWG v2/v3 and QUIC support. The universal APK
supports arm64-v8a, armeabi-v7a, x86 and x86_64. Install it over the previous
version to keep your settings and login.

AWG profile generation, import and sync are available in Settings. Enabling
QUIC clears the saved AWG profile after confirmation; applying AWG selects
native mode. Mode changes restart the engine while preserving node identity.

Build with Go 1.27.3 and `make release-signed`. Signing credentials are supplied
through the existing signing environment variables.

## Overview

This fork adds AWG and QUIC support to the open source Tailscale Android client.

## Install or upgrade

1. Download [the signed 1.104.4 APK](https://github.com/LiuTangLei/tailscale-android/releases/download/v1.104.4/tailscale-release-signed.apk).
2. Open the APK on your Android device and allow installation from your browser
   or file manager if prompted.
3. Install over your existing fork installation to keep your settings and login.

The same APK supports arm64-v8a, armeabi-v7a, x86 and x86_64. Release details
and checksums are on the [1.104.4 release page](https://github.com/LiuTangLei/tailscale-android/releases/tag/v1.104.4).

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

Use Go 1.27.3, Java 17 or later, and the checked-in Gradle wrapper for this release.

## Developing on a Fire Stick TV

On the Fire Stick:

* Settings > My Fire TV > Developer Options > ADB Debugging > ON

Then some useful commands:
```
adb connect 10.2.200.213:5555
adb install -r tailscale-debug.apk
adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity
adb shell pm uninstall com.tailscale.ipn
```

## Bugs

Please report fork issues in [this repository](https://github.com/LiuTangLei/tailscale-android/issues).

## Contributing

`under_construction.gif`

PRs welcome, but we are still working out our contribution process and
tooling.

We require [Developer Certificate of
Origin](https://en.wikipedia.org/wiki/Developer_Certificate_of_Origin)
`Signed-off-by` lines in commits.

## About Us

This fork builds on the [Tailscale Android client](https://github.com/tailscale/tailscale-android).

WireGuard is a registered trademark of Jason A. Donenfeld.
