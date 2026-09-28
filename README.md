# 📊 DroidTop

[Русский](README.ru.md) · **English**

A process and resource monitor for Android inspired by **htop**: which processes are running,
how much CPU and memory they use, how busy every core and the GPU are, all of it also available
in a small floating window on top of other apps.

## Screenshots

| Main screen | Process list | Process |
|:---:|:---:|:---:|
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="240"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="240"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="240"> |
| **htop table and overlay** | **Without root** | |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="240"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" width="240"> | |

## Download

Ready-made APKs are on the [Releases](https://github.com/Xtratter/droidtop/releases) page. Android 8.0 or newer is required.

## Features

- **Material 3 "liquid glass" design**: translucent cards on a soft colored background, colors follow
  the wallpaper (Android 12+), smooth animations. If you prefer the classic look, there is an htop table mode
- **Themes**: standard, follow system, AMOLED black, light, graphite and classic htop (⋮ → Settings → Theme)
- **Pinch to zoom in the htop table**: spread or pinch two fingers to make the table and meters text bigger
  or smaller (the size is remembered and can also be picked in the settings)
- **htop-style meters**: load and frequency of every core, memory, swap (zram), tasks,
  load average, uptime, CPU and battery temperature
- **Process list**: PID, USER, S, CPU%, MEM%, RES, THR, TIME+. Tap a column header to sort,
  tap again to reverse. Extra columns hide on narrow screens and come back in landscape
- **Process tree** (the "Tree" chip, like F5 in htop) shows who started whom: `init` → `zygote64` → apps → their
  child processes. Tap the icon to collapse a branch. The process card links to the parent and all children
- **GPU**: load, frequency, model and temperature for Adreno or Mali, shown as a card with a chart, a line in the htop table and in the overlay
- **History charts** of CPU, memory, GPU and battery power for the last 20 minutes, plus CPU and memory in the process card.
  Drag a finger over a chart to see the value at any moment. Spread or pinch two fingers to show from 40 seconds up to 20 minutes
- **Battery**: power in watts, current, voltage, temperature and a "≈ … left" / "full in …" estimate (no root needed)
- **App names** instead of `com.example.app`, search by name, PID or user
- **Tap a process** for details (command line, peak memory, swap, `oom_score_adj`) and actions:
  SIGTERM, SIGKILL, SIGSTOP/SIGCONT, force stop, open "App info"
- **Floating overlay** (⋮ → "Floating overlay"): CPU, core bars, memory, GPU and the top processes.
  Drag it with a finger, tap it to open DroidTop, close it from the notification.
  In the settings you choose what it shows, its size and width, the number of top processes and whether they are
  sorted by CPU or memory, background opacity, and you can lock it in place

## Access modes: normal, Shizuku, root

| | Normal | [Shizuku](https://shizuku.rikka.app/) | Root |
|---|:---:|:---:|:---:|
| Memory, swap, core frequencies, temperatures, battery | ✅ | ✅ | ✅ |
| CPU load (total and per core) | ❌ | ✅ | ✅ |
| GPU load and frequency (Adreno, Mali) | usually ❌ | depends on the ROM | ✅ |
| Process list and tree | own only | all | all |
| Force stop apps | ❌ | ✅ | ✅ |
| Kill any process | ❌ | shell only | ✅ |

These limits come from Android itself: since Android 7 an app only sees its own processes, and since Android 8 `/proc/stat` is hidden.
Pick the mode by tapping the chip next to the title or via ⋮ → "Access mode".

- **Shizuku, no root.** The free [Shizuku](https://shizuku.rikka.app/download/) app grants ADB permissions:
  install it, start its service via "Wireless debugging" (Android 11+) and choose the Shizuku mode.
  DroidTop will ask for permission. After a reboot the Shizuku service has to be started again.
- **Root**: KernelSU, Magisk or APatch. Allow DroidTop in your root manager.

> [!WARNING]
> Killing system processes (`system_server`, `surfaceflinger` and so on) can freeze or reboot the phone.
> DroidTop asks for confirmation, but be careful.

## How it works

No native libraries: the app keeps a shell (`sh` or `su`) open and every few seconds reads
`/proc/stat`, `/proc/meminfo`, `/proc/[pid]/stat`, frequencies from `/sys/devices/system/cpu`,
temperatures from `/sys/class/thermal` and GPU data from `/sys/class/kgsl` or `/sys/kernel/gpu`
with a single `grep` call — one short-lived process per update. Full process names come from `ps`,
only for new PIDs. While only the overlay is shown, names are looked up just for the busiest processes,
and when the screen is off polling stops completely.

```
app/src/main/java/io/github/xtratter/droidtop/
├── Shell.kt          ← persistent shell: sh, sh via Shizuku, or su
├── ProcParser.kt     ← polling script and /proc parsing
├── Sampler.kt        ← background polling thread shared by the screen and the overlay
├── Model.kt          ← Snapshot, ProcInfo, sort orders
├── Tree.kt           ← process tree (parents and children)
├── History.kt, Chart.kt, ChartView.kt       ← sample history and charts (with time zoom)
├── Battery.kt, BatteryCard.kt               ← battery current, power and estimate
├── Gpu.kt, GpuCard.kt                       ← GPU load and frequency from /sys
├── MainActivity.kt   ← screen, menu, search, table zoom
├── Ui.kt             ← Material You colors, "glass", background, animations
├── CpuCard.kt, MemCard.kt, ChipsView.kt     ← CPU, memory and summary cards
├── ProcItemView.kt, SortBar.kt, AppIcons.kt ← card-style process list
├── MetersView.kt     ← core and memory bars (htop table mode)
├── Table.kt, HeaderView.kt, ProcRowView.kt  ← process table
├── ProcessDialog.kt  ← process details and actions
├── SettingsDialog.kt, Prefs.kt              ← settings
└── OverlayService.kt, OverlayView.kt        ← floating overlay
```

## Building

```sh
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # → release build (R8), sign it with apksigner
./gradlew testDebugUnitTest    # /proc and GPU parsing tests
```

You need JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). The only library is the [Shizuku API](https://github.com/RikkaApps/Shizuku-API) (Apache-2.0).
A step-by-step guide to building on a phone (in Russian) is in the
[cat-hunt](https://github.com/Xtratter/cat-hunt/blob/main/docs/GUIDE.md) repository.

## License

[GPL-3.0-or-later](LICENSE)
