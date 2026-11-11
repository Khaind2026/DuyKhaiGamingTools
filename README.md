# DuyKhaiDex Gaming Tools v0.2

Samsung Android gaming display tuning project with a neon gaming icon, Root / Shizuku status indicators and an automatic debug APK build.

## Features
- Display overrides: logical size 720 / 1080 / 1440, aspect ratios (native, 16:9, 16:10, 21:9), DPI 160–640.
- Requests for supported refresh rates: 60–200 Hz, depending on Samsung firmware and hardware.
- Game FPS choices: 30, 45, 60, 90, 120, **144** and **165**. FPS settings are **requests** to Android GameManager, not frame-rate guarantees.
- Built-in game packages: PUBG Mobile VN / Global, Free Fire / Free Fire MAX, Delta Force Global / Garena, Call of Duty Mobile VN / Global. An editable custom package field is available.
- Root LED and an explicit **Request Root** button. Shizuku LED shows binder and authorization status, with a permission request button.
- **Only Root is an execution backend** for display/CPU changes in v0.2. Shizuku currently only reports connection and permission.
- CPU: ECO (70% of stock ceiling), BALANCED (90% of stock ceiling), SUPER (100% of stock ceiling). These are **stock-range maximum frequency caps, not overclocking**; no thermal protections are disabled. Not available on every kernel.
- Color Lab: saturation, vibrance-like slider, contrast and brightness on an **in-app preview only**, with a shortcut to Samsung display settings. No systemwide color modification yet.
- AUTO BOOT profile reapplication via BOOT_COMPLETED and JobScheduler. May be delayed by Samsung background restrictions.

## Build APK via GitHub Actions
1. Open **Actions** -> **Build DuyKhai Android APK**.
2. Select the latest successful run from `main` (or press **Run workflow**).
3. In **Artifacts**, download **DuyKhaiGamingTools-debug**.
4. Unzip and install `app-debug.apk` on a **spare rooted test device**.

GitHub Actions uses JDK 17, Android SDK 35, Gradle 8.7 and Android Gradle Plugin 8.6.1.

## Usage
1. Launch app, grant Root in Magisk/KernelSU/SukiSU Ultra. Confirm the Root indicator is green.
2. Optionally start Shizuku separately and grant its API permission; this does not replace Root for CPU/display in this build.
3. Choose size, DPI, refresh preference and a game package; enable AUTO BOOT if needed.
4. Choose an optional CPU cap mode. Controls cannot exceed the manufacturer's stock max frequency and may be blocked by Samsung's kernel.
5. Apply, verify changes, and revert when troubleshooting.

## Recovery
If an internal display override causes a bad layout, use ADB if available:

```sh
adb shell su -c 'wm size reset; wm density reset'
```

**Limits:** no device thermal-control bypass, no real overclock above manufacturer limits, no independent HDMI/DeX control, no global color changes, and no promises of 144/165 FPS. CPU thermal protection and operating-system constraints continue to apply.

No ads, telemetry or Internet permission.
