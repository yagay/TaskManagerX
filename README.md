# TaskManager — Root + LSPosed API 102 refactor

This is a from-scratch architectural refactor inspired by
`RohitKushvaha01/TaskManager`.

## Phase 1 goals

- Root-only runtime. Shizuku has been removed.
- Persistent `su` shell rather than starting a new root process for every refresh.
- Live CPU and RAM overview.
- Full Linux/Android process list.
- Search and sort by CPU, memory, name, or PID.
- PID kill and Android package force-stop.
- Installed-app label/icon mapping where a numeric UID is available.
- Modern libxposed API **102.0.0** module entry.
- Static LSPosed scope is `android` (system framework/system_server).
- No network-speed UI yet.
- No floating window yet.

## Architecture

```
UI (Compose)
  |
MainViewModel
  |
  +-- SystemStatsRepository ----+
  +-- ProcessRepository --------+--> RootShell --> persistent `su`
  +-- FrameworkRepository ------+
  |
  +-- TaskManagerModule (libxposed API 102, android scope)
```

Root is the primary data/control plane. LSPosed is an enhancement plane.
This separation is intentional: a bad framework hook must never be required
for the task manager itself to function.

## Why the original daemon/Shizuku path was replaced

The upstream project starts a native daemon through Shizuku or root and then
talks to it through process stdin/stdout. This refactor removes the Shizuku
state machine and makes privilege ownership explicit: all phase-1 privileged
operations go through one root shell. The API 102 module is kept deliberately
minimal until a system hook is actually needed.

## Planned phase 2: per-app real-time network speed

The next layer should be **UID based**, not foreground-app based:

1. Obtain system-wide UID RX/TX counters.
2. Snapshot at a configurable interval (500–1000 ms).
3. Compute deltas per UID.
4. Map UID -> one or more packages.
5. Keep background and system UIDs.
6. Sort active UIDs by live throughput.
7. Add interface filters (Wi-Fi/mobile/VPN) after correctness is established.

A backend abstraction should allow:
- eBPF/netd/NetworkStats based root backend
- LSPosed system_server compatibility backend
- public Android API fallback where useful

No overlay/floating UI is part of phase 1.

## Build

Expected environment:

- Android SDK 37
- Java 17
- Gradle 9.4.1
- Android Gradle Plugin 9.2.0
- libxposed API 102.0.0

The Gradle wrapper properties are included. A wrapper JAR is intentionally not
vendored in this generated source archive. If your checkout does not already have
one, run `gradle wrapper --gradle-version 9.4.1` once or let Android Studio recreate it.

## LSPosed

Modern API metadata:

- `META-INF/xposed/java_init.list`
- `META-INF/xposed/module.prop`
- `META-INF/xposed/scope.list`

Scope:

```
android
```

Enable the module in LSPosed and enable the Android/System Framework scope.

## Attribution

The product concept and feature baseline were studied from:

- RohitKushvaha01/TaskManager — Apache License 2.0

This refactor was written from scratch rather than copying the upstream
application source files. See `UPSTREAM.md`.
