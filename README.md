# DuyKhai Gaming Tools v0.1

Android application for rooted Samsung phones. Native Kotlin interface with a dark cyan/violet gaming theme.

## Features
- Set logical internal-display size: 720p, 1080p or 1440p short edge.
- Override logical screen aspect ratio: native, 16:9, 16:10, 21:9.
- Enable a custom DPI from 160 to 640.
- Request the system's preferred min/peak refresh rates at 60, 90, 120, 144, 165 or 200Hz. Unsupported display modes are not unlocked.
- Optionally send a per-game target FPS to Android GameManager. Not guaranteed for every Samsung game/ROM.
- Save profile and **AUTO BOOT**: after `BOOT_COMPLETED`, use a short `JobScheduler` task to re-apply saved profile with root permissions.
- Reset display size/density/refresh overrides and selected game's GameManager FPS setting.

## How to build on GitHub
1. Open the repository's **Actions** page.
2. Open **Build DuyKhai Android APK** and select **Run workflow**. Pushes to `main` also trigger a build.
3. When the build succeeds, download the **DuyKhaiGamingTools-debug** artifact, unzip it, and install `app-debug.apk` on a test phone.

The workflow installs Android SDK 35, JDK 17 and Gradle 8.7. No Gradle wrapper needed to build using GitHub Actions.

## Auto Boot
1. Install and open the app.
2. Save your settings by pressing **APPLY GAMING PROFILE** with **AUTO BOOT** enabled.
3. In Magisk, KernelSU or SukiSU Ultra, grant root permission permanently.
4. Reboot and unlock the phone. Samsung background restrictions may delay background tasks; force-stopped applications generally cannot handle startup events.
5. If the system displays an error, reset the overrides and turn off Auto Boot.

## Important limits
This is experimental Android code. Test first on a backup device with ADB access. To recover display size and density:

```bash
adb shell su -c 'wm size reset; wm density reset'
```

It modifies the **internal logical display**, not the physical panel's actual mode and not an independent HDMI/DeX display. FPS and Hz are requests; hardware/ROM/game restrictions still apply. There is no internet permission or telemetry.
