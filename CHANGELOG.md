# Changelog

[Русский](CHANGELOG.ru.md) · **English**

## 1.5 — 2026-09-28

- Pinch to zoom in the htop table: spread or pinch two fingers to make the table and meters text bigger or smaller (7 to 28 sp); the size is remembered
- Time zoom on charts: use two fingers on a chart to show from 40 seconds up to 20 minutes; all charts on the main screen change together
- The main charts (CPU, memory, GPU, battery) now keep 20 minutes of history instead of 5
- More font sizes in the settings (8–24 sp)
- README and changelog are now also available in English

## 1.4 — 2026-09-28

- GPU card: load, frequency (current / max), model and temperature, with a history chart
- Supports Adreno (Snapdragon, /sys/class/kgsl) and Mali / Tensor (/sys/kernel/gpu). Without access to these files the card is hidden; when only the frequency is readable, its share of the maximum is shown
- GPU line in the classic htop table and in the overlay

## 1.3 — 2026-09-27

- Shizuku mode: all processes and CPU load without root (ADB permissions through the Shizuku app)
- Choose the access mode (normal / Shizuku / root) by tapping the chip next to the title
- History charts: CPU, memory and battery power in the cards, CPU and memory in the process card; drag a finger over a chart to see the value at any point
- Battery card: power, current, voltage, temperature, time until empty or full; battery line in the overlay

## 1.2 — 2026-09-27

- Process tree: the "Tree" chip shows who started whom; tap the icon to collapse a branch
- The process card shows the parent and child processes, tap to open them
- Core bars now show low load honestly (5 % and 30 % used to look the same)
- Dimmed area under the status bar and a denser "glass" top bar, so scrolled text no longer gets in the way
- DroidTop itself uses less CPU: the background is drawn once, animations are shorter, the list is not rebuilt without need
- "Running" is counted by the kernel (/proc/loadavg), peak memory and swap of a process are shown in MB
- Screenshots in the README and for F-Droid

## 1.1 — 2026-09-27

- New Material 3 "liquid glass" design: translucent cards with a highlight, a floating top bar, colors from the wallpaper (Android 12+)
- "Processor" card (big load number, temperature, core bars) and "Memory" card (used / cache / swap), summary chips
- Card-style process list: app icons, a state dot, the metric of the chosen sort order on the right; sort chips
- Smooth value animations, glass dialogs with a blurred background (Android 12+), a new overlay
- The classic htop table is still there: ⋮ → "Classic htop table"
- Fixed: on Android 15 the top of the screen went under the title; long user names overlapped the next column

## 1.0 — 2026-09-27

First version.

- htop-style process list: PID, user, state, CPU %, memory %, RES, threads, CPU time; tap a column to sort
- Meters: load and frequency of every core, memory, swap, tasks, load average, uptime, CPU and battery temperature
- Root mode (KernelSU / Magisk / APatch): all processes and CPU load; without root: memory, frequencies and temperatures
- Process details and actions: SIGTERM / SIGKILL / SIGSTOP / SIGCONT, force stop, "App info"
- Floating overlay on top of other windows with the top processes
- App names instead of package names, search, interval and font settings
- Russian and English interface
