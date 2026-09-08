package com.rk.taskmanager.data

import com.rk.taskmanager.model.FrameworkState
import com.rk.taskmanager.root.RootShell

class FrameworkRepository(
    private val shell: RootShell,
) {
    suspend fun detect(): FrameworkState {
        val result = runCatching {
            shell.execute(
                """
                if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ] || [ -d /data/adb/modules/lsposed ]; then
                  echo LSPOSED
                elif [ -d /data/adb/modules ]; then
                  ls /data/adb/modules 2>/dev/null | grep -Ei 'lsposed|lspd' | head -n 1
                fi
                """.trimIndent(),
                4_000
            ).stdout.trim()
        }.getOrDefault("")

        return if (result.isNotBlank()) {
            FrameworkState(
                detected = true,
                detail = "LSPosed framework files detected. API 102 module scope: android"
            )
        } else {
            FrameworkState(
                detected = false,
                detail = "LSPosed not detected from the root filesystem"
            )
        }
    }
}
