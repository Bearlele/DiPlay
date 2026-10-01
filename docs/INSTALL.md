# Install and connect

1. Park the car. Download `DiPlay-0.2.8.apk` from the official GitHub release linked on the website.
2. Install on the Android head unit using its supported APK installation method. Do not install on the iPhone. The official release updates earlier official DiPlay builds with the same signing key.
3. Open DiPlay. Grant the permissions requested for the features you use: Bluetooth/Nearby devices, Wi-Fi/Location on older Android, and microphone for Siri/calls. Allow notifications for connection controls.
4. Close other phone-projection apps before connecting.

The Android 8.0+ test APK uses the separate `com.shihab.diplay.hudtest` package and a debug signing key. It can coexist with the official app but does not update it or share its saved settings. Install the test APK on the car and configure its hotspot settings separately.

## Wireless

Built-in car hotspot is the default. Open **Settings → Connection setup → Built-in car hotspot**, turn on the car hotspot, and save its exact name and password. Choose the hotspot band in the car's settings; if the Android 8.1 car provides only 2.4 GHz, use it. DiPlay handles the channel automatically. Keep Bluetooth and Wi-Fi enabled on the iPhone, pair it with the car, then tap **Connect phone**. The phone joins automatically after the Bluetooth handshake; manual Wi-Fi joining and ADB are not required. **Choose iPhone** changes the selected paired device. See [the setup guide](CONNECTION_SETUP.md).

Wi-Fi Direct remains an alternative, including an Android 8.0+ legacy path. Its frequency is chosen by the firmware and must be read from the driver. Wireless CarPlay video and audio are confirmed on a physical Android 8.0 Xiaomi Mi 6. The Local hotspot option has been removed; existing selections migrate to built-in hotspot. USB remains available. Verify operation on the target head unit before relying on it.

## USB

Connect the iPhone to a USB **data** port with a data-capable cable and choose **Connect with USB**. Approve USB access, Trust/CarPlay and the local VPN permission if requested. The local VPN carries the USB network link; it is not an internet VPN service. Charge-only ports/cables cannot work.

## Settings

Swipe down with three fingers in CarPlay to open DiPlay settings, or return to the home screen. Icon/text size, resolution and frame rate use **Apply and reconnect** during an active session. A selection alone does not apply; Cancel preserves the old setting. When disconnected, **Save** applies to the next connection. Other settings also apply on the next connection.

Start with 30 fps, Efficient video (HEVC) off and Default icon/text size. Try 80% or 60% resolution for a slower head unit. Some iPhone/head-unit combinations still ignore icon/text scaling.

## Connection recovery and reports

If reinstalling left an old group, close other projection apps, then use **Settings → Wireless connection help → Reset CarPlay Wi-Fi**. DiPlay asks before removing an unrecognized Wi-Fi Direct group. Updating in place is preferable to uninstalling.

Use **Settings → Diagnostics → Save diagnostic report** after reproducing a problem. Android 10+ saves to **Downloads/DiPlay**; Android 9 uses a document picker. Review the file, then attach it to a GitHub issue with car model, DiLink/Android versions, iPhone/iOS, transport and reproduction steps. Nothing is uploaded automatically.

APK installation restrictions are controlled by your car's firmware. ADB is optional if your car supports it, not an app runtime requirement:

```sh
adb install -r DiPlay-0.2.8.apk
```

Only use a trusted computer. A different signing certificate cannot update this build; do not uninstall until you have saved any reports you need.

## BYD navigation

See [BYD navigation displays](BYD_NAVIGATION.md) for the firmware scope, map metadata requirements, settings and cleanup behavior. No runtime ADB starter is required.
