package com.rk.taskmanager.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

data class ShellResult(
    val code: Int,
    val stdout: String,
)

class RootShell {
    private val mutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var reader: BufferedReader? = null

    suspend fun isRootAvailable(): Boolean {
        return runCatching {
            execute("id -u", 4_000).stdout.trim().lineSequence().lastOrNull() == "0"
        }.getOrDefault(false)
    }

    suspend fun execute(command: String, timeoutMs: Long = 8_000): ShellResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    ensureShell()
                    val marker = "__TM_${UUID.randomUUID().toString().replace("-", "")}__"
                    val w = requireNotNull(writer)
                    val r = requireNotNull(reader)

                    w.write(command)
                    w.newLine()
                    w.write("printf '\\n$marker:%s\\n' \"$?\"")
                    w.newLine()
                    w.flush()

                    val lines = mutableListOf<String>()
                    var exitCode = -1

                    withTimeout(timeoutMs) {
                        while (true) {
                            val line = r.readLine()
                                ?: throw IllegalStateException("Root shell closed")
                            if (line.startsWith("$marker:")) {
                                exitCode = line.substringAfter(':').trim().toIntOrNull() ?: -1
                                break
                            }
                            lines += line
                        }
                    }

                    ShellResult(exitCode, lines.joinToString("\n").trim())
                } catch (e: TimeoutCancellationException) {
                    reset()
                    throw e
                } catch (t: Throwable) {
                    reset()
                    throw t
                }
            }
        }

    private fun ensureShell() {
        val p = process
        if (p != null && p.isAlive && writer != null && reader != null) return

        reset()
        val newProcess = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()

        process = newProcess
        writer = BufferedWriter(OutputStreamWriter(newProcess.outputStream))
        reader = BufferedReader(InputStreamReader(newProcess.inputStream))
    }

    fun close() {
        reset()
    }

    private fun reset() {
        runCatching { writer?.write("exit\n") }
        runCatching { writer?.flush() }
        runCatching { process?.destroy() }
        writer = null
        reader = null
        process = null
    }
}
