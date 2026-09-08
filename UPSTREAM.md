# Upstream reference

Upstream project:
- https://github.com/RohitKushvaha01/TaskManager

Upstream license:
- Apache License 2.0

Observed upstream feature baseline:
- CPU monitoring
- RAM monitoring
- Linux process listing
- process details
- process kill
- Android app kill
- Shizuku/root working modes
- native taskmanager daemon

This refactor retains the general task-manager purpose but replaces the
privilege/control architecture with Root + modern libxposed API 102.
