# TaskManagerX — Root + LSPosed API 102

A from-scratch Root-first task manager inspired by `RohitKushvaha01/TaskManager`.
The original Shizuku/native-daemon control path has been replaced with a persistent
Root shell and a modern libxposed API 102 integration point.

## Current features

### Processes

- Full Android/Linux process scan from `/proc`.
- PID, PPID, UID and Linux user.
- CPU usage, RSS and virtual memory.
- Thread count, nice value and process state.
- Start time and elapsed time.
- Executable path, cgroup and `oom_score_adj`.
- Foreground/perceptible low-level approximation.
- User app, system app and Linux-process filters.
- Shared UID -> multiple package mapping.
- App labels and icons.
- Search and sorting by RAM, CPU, name or PID.
- Persistent Pin/Unpin.
- Parent-process navigation.
- Long-press detail values to copy.
- Root `kill -9 PID` and Android `am force-stop`.
- Optional kill confirmation.
- Configurable automatic refresh (500/800/1000/2000 ms; default 800 ms).

### Resources

- Real-time CPU, RAM, SWAP and GPU history graphs.
- SoC, architecture, ABI and core count.
- CPU governor, temperature, uptime and load average.
- Per-core minimum/current/maximum CPU frequency.
- RAM available/cached/buffer details.
- GPU vendor and renderer.
- OpenGL and GLSL versions.
- Vulkan support/API version.
- GPU load and min/current/max frequency when exposed by the device kernel.

### Network

- Real-time per-UID download and upload speed.
- Shows all active UIDs, including background and system apps.
- Shared UID package lists are preserved.
- Active entries are sorted by total throughput.
- Modern Android backend: Root `dumpsys netd trafficcontroller` -> eBPF `mAppUidStatsMap`.
- Legacy fallback: `/proc/net/xt_qtaguid/stats`.
- Public `TrafficStats` is kept only as an own-UID diagnostic fallback because Android N+
  blocks cross-UID access through that API.

The network page intentionally shows only UIDs that generated traffic during the latest
sample. No external floating window is implemented.

## Architecture

```text
Compose UI
   |
MainViewModel
   |
   +-- ProcessRepository ------ /proc
   +-- SystemStatsRepository -- /proc + /sys
   +-- GpuRepository ---------- EGL/GLES + Root sysfs
   +-- NetworkRepository ------ Root netd eBPF / qtaguid
   +-- SettingsRepository
   +-- FrameworkRepository
   |
Persistent RootShell ---------- su

TaskManagerModule ------------- libxposed API 102, android scope
```

Root is the primary data/control plane. LSPosed is an optional enhancement layer and is
not required for the core task manager to remain usable. Keeping the two planes separate
reduces `system_server` hook risk and makes Android/OEM updates easier to support.

## Why Shizuku / the upstream daemon are not used

The upstream application can start a native task-manager daemon through Shizuku or Root
and communicate with it through stdin/stdout. TaskManagerX deliberately replaces that
state machine with one persistent Root shell and direct `/proc`, `/sys`, EGL and netd
collection. This keeps privilege ownership explicit and avoids making UI functionality
dependent on a second daemon process.

## Build

Expected environment:

- Android SDK 37
- Java 17
- Gradle 9.4.1
- Android Gradle Plugin 9.2.0
- libxposed API 102.0.0

GitHub Actions builds `:app:assembleDebug` and uploads `TaskManagerX-debug` as an artifact.

## LSPosed

Modern API metadata:

- `META-INF/xposed/java_init.list`
- `META-INF/xposed/module.prop`
- `META-INF/xposed/scope.list`

Static scope:

```text
android
```

The API-102 module entry is intentionally conservative. Root provides the current feature
set; framework hooks can be added later only where they materially improve accuracy or
compatibility.

## Attribution

Feature behaviour and product ideas were studied from:

- RohitKushvaha01/TaskManager — Apache License 2.0

TaskManagerX is a from-scratch implementation rather than a copy of the upstream source.
See `UPSTREAM.md`.
