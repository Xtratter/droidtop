# Changelog

[Русский](CHANGELOG.ru.md) · **English**

## 1.9.1 — 2026-09-28

- The CPU card no longer hides under the top bar and status bar when the app starts: the space for the bar now belongs to the list header instead of the list padding, which ListView kept stale while the bar size arrived
- Device info: the kernel line shows just the release; the compiler and build date moved to a separate "Kernel build" line
- Open files: unnamed Unix sockets show their number (`unix socket:[12345]`)
- Stats recording: no duplicate row when the same sample is redelivered after app icons load

## 1.9 — 2026-09-28

- **Device info** (⋮ → Device info), in the spirit of AIDA64: device and model, Android, security patch, firmware, kernel, SELinux, bootloader; SoC with core clusters (Cortex names, frequency ranges, governor); RAM, swap and zram, storage size and type; display resolution, diagonal, density, refresh rates, HDR; GPU with OpenGL ES and Vulkan versions; battery health, technology, charge cycles, design and current capacity; cameras; sensors; all temperature sensors. Tap a line to copy it, long-press a section title to copy the section, or "Copy all"
- **Copy to clipboard**: ⋮ → "Copy summary" (CPU, cores, memory, swap, GPU, battery, load, top 10 processes), "Copy details" in a process card, "Copy" in the thread and open-file lists
- **Record an app's stats to a file**: "Record stats to a file" in a process card (root or Shizuku) writes a CSV to `Download/DroidTop/` on every sample — CPU, memory, process and thread count of all the app's processes, plus system CPU, CPU temperature, memory, swap, GPU and battery. It keeps going in the background with its own notification; stop it there, in ⋮ or in the process card. The final notification shows the averages and maximums and opens the file
- The htop table switch moved from the ⋮ menu to Settings only

## 1.8 — 2026-09-28

- **Threads** of a process (the "Threads" button or tile in its card): every thread with its CPU %, updated live, state, the core it last ran on and nice; the busiest on top
- **Open files** of a process: files, devices, pipes, sockets and other descriptors grouped with counts; sockets show protocol, addresses and TCP state (`TCP 10.0.0.5:51234 → 142.250.1.1:443 ESTABLISHED`) or the Unix socket path. The text can be selected and copied; "Refresh" re-reads the list. Other apps' files need root

## 1.7.1 — 2026-09-28

- Overlay: on the narrow width the GPU and battery values no longer run over their labels — parts that don't fit (temperature, then clock) are dropped
- Core bars (overlay and CPU card) and the used-memory bar are no longer faded: their gradient could pick up a semi-transparent color from the element drawn before
- Settings: the overlay summary keeps "CPU" in capitals ("load CPU" instead of "load cpu")

## 1.7 — 2026-09-28

- **Themes** (⋮ → Settings → Theme): standard (as before), follow system (standard or light with Android's dark mode), AMOLED black, light, graphite and classic htop
- **Flexible overlay settings**: choose what it shows (CPU load, temperature, cores, RAM, GPU, battery), size 70–150 %, width (narrow / normal / wide), up to 10 top processes sorted by CPU or by memory, and "Lock position" so it can't be dragged by accident

## 1.6.2 — 2026-09-28

- The "last N min" label above charts no longer jumps (for example to "6 min") after the screen was off or the app was in the background: pauses and quick extra updates are not counted in the time scale

## 1.6.1 — 2026-09-28

- Process details are now live: the CPU, memory, threads and CPU time tiles and the info lines update with every sample, not only when the dialog opens (while you select text there, it is left alone)

## 1.6 — 2026-09-28

Uses much less CPU and battery:
- One process per update instead of about seven: all files are read with a single `grep`
- Faster parsing of `/proc/[pid]/stat` without thousands of temporary strings
- Charts are drawn once per update and cached, not on every animation frame
- The overlay no longer animates: each animation frame made the system redraw the whole screen under it
- The overlay stops polling while the screen is off; when it is the only thing shown, names and icons are looked up only for the busiest processes
- The main screen is not updated under an open dialog (the blur behind the dialog is no longer recomputed)
- Release build with R8: the APK is 130 KB instead of 1 MB, and the code runs faster than the old debug build

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
