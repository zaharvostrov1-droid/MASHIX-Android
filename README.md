# MASHIX Android starter (3.4)

This is the first native Android wrapper for the supplied MASHIX 3.4 HTML interface.
It keeps the existing page in `app/src/main/assets/index.html` and adds a native WireGuard
backend using the WireGuard Android tunnel library.

## Current scope

- Existing MASHIX HTML interface is bundled unchanged as the starting UI.
- Native Android `VpnService` permission flow is declared.
- Import of a WireGuard `.conf` file through Android's document picker.
- Connect/disconnect calls are routed to WireGuard's `GoBackend`.
- The power button is rebound to the native VPN connection; it no longer runs the old demo timer.

## Important limitations

- This initial native build supports **WireGuard `.conf` files only**. The HTML demo's other
  subscription-link schemes (VLESS, VMess, Trojan, Shadowsocks, etc.) are not implemented as
  native protocols and must not be presented as working connections yet.
- A valid configuration for a VPN server is required. This project does not include VPN servers,
  credentials, private keys, or a subscription service.
- No APK is included. Build and test the project in Android Studio with Android SDK 35 and JDK 17.
- The Gradle dependency needs internet access on the build device/computer to download libraries.

## Build

1. Open this folder (`MASHIX-Android`) in Android Studio.
2. Allow Gradle sync to complete.
3. Connect an Android device with developer options/USB debugging enabled, or use an emulator.
4. Run the `app` configuration. Android will ask for VPN permission on the first connection.
5. In MASHIX, open Locations and tap **Импортировать WireGuard .conf**. Select a valid config file,
   then tap the large power button and approve Android's VPN prompt.

## Package identity

The current application ID is `app.mashix.vpn`. Change it before publishing if a different
permanent package ID is required.
