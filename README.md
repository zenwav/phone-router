# Phone Router

An Android hotspot controller and LSPosed module for rooted phones. Share the
phone's mobile connection and manage Wi-Fi, clients, SMS, and shared storage
from a browser. The app and web interface are in English.

[中文说明](docs/README.zh-CN.md) · [Releases](https://github.com/wfgzsdq/phone-router/releases)

**Development disclosure:** This project was developed with assistance from
Claude and GPT.

## Features

- Start, stop, and restart Android's internet-sharing Wi-Fi hotspot.
- Configure SSID, password, security, band, hidden SSID, and client limit;
  display a Wi-Fi QR code and block/unblock clients.
- Set the Wi-Fi gateway to `192.168.43.1/24` with a fixed-IP hook adapted from
  [SoftApHelper](https://github.com/XhyEax/SoftApHelper).
- View traffic and estimate remaining monthly allowance from Android's
  device-wide mobile-data counters and a manually configured plan.
- Apply aggregate upload/download limits with `tc`; schedule hotspot on/off.
- Read SMS conversations and select a SIM for sending messages. List previews
  fit their current width and end with `……`; conversation bodies stay complete.
- Browse, upload, download, rename, create, and delete files under
  `/storage/emulated/0`.
- Run a foreground management service and optionally install a KernelSU boot
  module to start it after boot.

## Requirements and installation

- Android 10+; root with `su` support for switching to system UID 1000.
- LSPosed for the fixed-IP feature. Android internals and OEM restrictions vary;
  the minimum SDK is not a guarantee that every feature works on every ROM.
- Install the signed APK from Releases and grant root to **Hotspot Control**.
- In **LSPosed → Modules → Hotspot Control**, enable the module and select **System
  Framework (`android`)** plus the installed recommended Tethering packages:
  - `com.android.networkstack.tethering.inprocess`
  - `com.android.networkstack.tethering`
  - `com.google.android.networkstack.tethering.inprocess`
  - `com.google.android.networkstack.tethering`
- Reboot after enabling the module, changing scope, or updating hook code.
- Start the hotspot, connect another device, and open
  `http://192.168.43.1:8080`. The web password is randomly generated on first
  launch and shown in the app; change it there.

The module is included in this APK. Do not also enable SoftApHelper's equivalent
hook. Only its Wi-Fi fixed-IP behavior is included, not its other tether types,
metered flag changes, channel locking, or bandwidth modifications.

Release builds use a dedicated signing key. They cannot replace an existing
debug-signed installation in place. Keep/export any required app settings before
manually removing a debug build; this repository's build scripts do not uninstall
apps or erase their data.

## Build from source

Install JDK 17 and Android SDK platform 34 / build-tools 34.0.0. Set
`ANDROID_HOME` or create a local `local.properties` containing `sdk.dir=...`.

```sh
# Windows: replace ./gradlew with .\gradlew.bat
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Gradle compiles `shim/src/main/java` with `javac`, converts it to a dex jar with
D8, and packages it as `assets/shim.jar`. There is no checked-in prebuilt shim
and no separate manual compilation step. `:app:buildShim` builds only the shim.

For a signed release, copy `keystore.properties.example` to
`keystore.properties` and supply your own keystore path, alias, and passwords:

```sh
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

With signing configured, the output is
`app/build/outputs/apk/release/app-release.apk`. Without it, Gradle produces an
unsigned release APK, which Android will not install.

CI reads the signing key from the `RELEASE_KEYSTORE_BASE64`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`
repository secrets, then verifies the signed APK before uploading it. See
[docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) for generating a key and
configuring the secrets. Never commit a keystore or real signing properties.
Keep the signing key backed up to retain the ability to publish compatible
updates.

## Source layout

| Directory | Purpose |
| --- | --- |
| `app/src/main/java/com/phonerouter/hotspot/` | App UI, service, boot and schedule receivers |
| `.../hotspot/` | Hotspot configuration, clients, traffic, limits, schedules |
| `.../root/` | Root shell and privileged shim client |
| `.../xposed/` | LSPosed fixed-IP hook and compatibility checks |
| `.../feature/` | SMS and shared-storage operations |
| `.../web/` | NanoHTTPD static content and authenticated REST API |
| `app/src/main/assets/web/` | HTML, CSS, JavaScript, and bundled QR library |
| `shim/src/main/java/` | System-UID Android API bridge |
| `app/src/test/` | Host-side hook eligibility and conflict-check tests |

## Behavior and validation limits

- The web server uses HTTP and binds all interfaces. Reachability depends on
  routing/firewall configuration; requests are not restricted to the hotspot
  subnet. Treat this as a trusted-network administration tool with root-backed
  operations. It is not designed for direct Internet exposure.
- Plan remainder is an estimate from device-wide mobile usage, not a carrier
  balance query or per-client hotspot accounting. Speed limits are aggregate.
- Fixed-IP hooks retain known prefix-conflict checks. On conflict or unsupported
  internal layouts, Android may choose another address. The app's current
  hotspot status detection and management URL assume the fixed gateway.
- On Android 15+, the upstream fallback permits replacement if the old private
  address coordinator is absent; that path cannot perform the same conflict check.
- Changing Wi-Fi settings or the blacklist restarts the hotspot.
- Historical runtime checks used Redmi K40 (alioth), Android 13 / HyperOS,
  with KernelSU. The SMS preview fix was checked against the device's served UI
  at six viewport widths (320–1440 px). Host tests cover reflection, interface
  filtering, call stacks, and conflict handling. LSPosed injection and DHCP
  allocation for this new module still require a reboot-based device check.

See the Chinese guide for the API table and fixed-IP troubleshooting. LSPosed
logs use the prefix `[PhoneRouter/SoftApHelper]`.

## License and credits

Original project code is available under the [MIT License](LICENSE).
Adapted SoftApHelper code and bundled QRCode.js retain their own MIT notices;
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Web-management design ideas
were inspired by [UFI-TOOLS](https://github.com/kanoqwq/UFI-TOOLS).
