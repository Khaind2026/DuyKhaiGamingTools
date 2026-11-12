# DuyKhaiDex Gaming Tools v0.6.0

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

## New in 0.3.0 — monitoring and shop contact
- Live read-only performance dashboard in the app: estimated *game-layer presentation FPS*, CPU thermal sensor reading and CPU clock rate by frequency policy.
- Opt-in draggable gaming **floating HUD** started from the app, with Android overlay permission and a notification while active. Stop by tapping the X on the HUD or the app button.
- FPS uses SurfaceFlinger timestamps on the selected game layer (when Root or connected Shizuku allows access); it is NOT the chosen FPS setting, the refresh rate, or a guaranteed accurate value. The selected game must be rendering; unsupported devices show a dash (—).
- CPU temperature is a best-effort **CPU/AP/SOC thermal-zone estimate**; different Samsung kernels expose different sensors. CPU clocks reflect `scaling_cur_freq` by policy in MHz. Unavailable values display —.
- Monitoring runs only while the app is visible or the user-enabled floating HUD is active. HUD does not auto-start on reboot.
- Removed numeric prefixes from all major in-app section headings.
- Added Samsung DeX sales contact **Zalo 0862134811**, with an optional contact button.
- May not display all sensors on Samsung One UI 7/8 without root/kernel access. This is a diagnostic tool, not a hardware temperature guarantee.

## v0.4 – Display/monitor controls
- Independent screen-Hz preference and GameManager FPS settings. Optional `cmd display set-user-preferred-display-mode` only for modes physically reported by the internal display, with command read-back. The OS may still override it (power/thermal/game policy).
- Custom logical screen width and height (480–3840 pixels, up to 10 MP) with custom DPI input (120–800). Tap reset or use ADB recovery if invalid.
- Explicit, once-per-process ROOT permission request: the app no longer probes `su` on each resume/monitor tick. To avoid repeated manager prompts, choose permanent authorization in Magisk/KernelSU and grant only the app you trust.
- FPS uses AOSP SurfaceFlinger `--timestats` on the selected game surface and reports lack of data instead of substituting screen refresh rate. CPU sensor fallback uses `dumpsys thermalservice`; readings vary by device/kernel.
- Floating HUD has live size control 60–160% via app slider or HUD +/- buttons. Approximate FPS is an average over a short recent sampling period, not instantaneous render FPS.
- CPU ECO/BALANCED/SUPER adjusts read-back verified stock-range min/max policies without disabling any thermal management. Not supported on all Samsung devices.
- Original offline neon galaxy with space-crewmate illustrations. Game visuals and original branding remain unchanged.
- Retained SamsungDeX support Zalo: 0862134811.

**No manufacturer-exceeding overclock or thermal safety bypass; no guarantee of 144/165/200 Hz or game FPS.**

## v0.5.0 — Rebuilt native neon gaming dashboard
- New launch activity uses a dedicated multi-screen space/gaming interface inspired by the creator's visual reference. Artwork is drawn directly in Android (not an inert screenshot).
- Home: DuyKhaiDex branding, crown-wearing space crewmates, neon purple/blue panels, interactive 3x3 tile grid, Root/Shizuku indicators, best-effort FPS/CPU temperature/clocks and clickable Zalo 0862134811 contact banner.
- Screens: **Tối Ưu Game**, **Khóa Hz**, **Tỷ Lệ Màn Hình**, **CPU & Hiệu Năng**, **Màu Sắc & Hiển Thị**, **HUD Theo Dõi**, **Quản Lý Game**, **Công Cụ Khác**, and **Cài Đặt**. The old advanced layout is still available under Other Tools.
- FPS game and screen Hz live on separate pages; frequency requests are limited to physically advertised modes, no absolute hard-lock guarantee.
- Custom width/height and DPI can be entered from the custom display screen; use reset or ADB recovery if the layout becomes unusable.
- HUD controls now adjust 60–160% size, opacity, corner, and visible metrics, with draggable overlay and +/- controls.
- Root requests are user-initiated and cached while the app process is alive. Assign persistent permission in Magisk / KernelSU if you wish to avoid future prompts across app starts.
- Color Lab preview is experimental; color sliders cannot modify Samsung's whole-screen colors without device-specific code.
- Root CPU ECO/BALANCED/SUPER remains confined to supported stock min/max limits with manufacturer thermal safety preserved.
- The dashboard visuals intentionally recreate the **theme** of the supplied concept while keeping native Android accessibility and working buttons; exact rendered illustration, fonts and shading can vary.

## v0.6.0 – Root shell, external monitor, Fold/Flip and orientation controls
- Reusable persistent `su` command shell replaces a fresh `su -c` for each FPS/CPU/display command, eliminating repeated privilege requests caused by the app while the process and shell remain alive. The Root grant remains controlled by Magisk/KernelSU/SukiSU and their notifications/settings. Root permission is requested explicitly from the Root tile; allow persistent permission in your trusted root manager if desired.
- Each command runs in a subshell with its exit status recorded; failure of a Samsung-only command no longer kills the parent Root shell.
- CPU reset now restores stock `cpuinfo_min_freq` and `cpuinfo_max_freq`, not SUPER mode.
- New fold-state screen inspects `cmd device_state print-states`, then offers **cover/outer** versus **inner/main** only when the ROM reports matching supported state IDs. An unconfirmed override is scheduled to reset after 20 seconds while the activity is alive. This is experimental; keep ADB recovery accessible. It cannot repair broken or physically removed panels.
- New rotation page uses WindowManager's `user-rotation` and orientation override for the default or detected external display, with a reset option. Some Samsung/DeX apps may override rotation.
- Screen size page adds FHD 1920×1080, 2K 2560×1440 and 4K 3840×2160 logical presets. These do not increase panel resolution.
- FPS HUD now uses frameless, draggable text; user can control size/opacity and turn it off from the ongoing notification.
- External HDMI/DeX FPS uses SurfaceFlinger layer statistics only when an external-tagged layer can be identified. The **display refresh rate (Hz) is shown separately** and must never be substituted for actual presented FPS. Some ROMs do not expose external-layer statistics.
- Brighter neon/cosmic UI colors throughout.
- CPU thermal safety remains enabled. Display state requests, per-display rotation, refresh rate and external FPS require compatible firmware/kernel and test hardware.

**Compatibility:** No reliable universal solution to make an unsupported game permit 144/165 FPS or to override game/device compatibility checks by changing a device identifier. This release does not claim to spoof RMX5070 or bypass app identification.
