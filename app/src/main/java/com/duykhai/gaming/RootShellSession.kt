package com.duykhai.gaming

import java.io.BufferedWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One reusable command shell instead of starting su for every command/poll.
 * Command output is delimited by a unique exit-code sentinel.
 * The reader always drains stdout; the entire session is killed on timeout.
 */
class ShellSession(private val argv: List<String>) {
    private val guard = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var output: LinkedBlockingQueue<String>? = null
    private var counter = 0L

    fun isAlive(): Boolean = synchronized(guard) { process?.isAlive == true }

    fun execute(command: String, timeoutMs: Long = 12000L): Array<String> = synchronized(guard) {
        if (command.isBlank()) return@synchronized arrayOf("2", "Lệnh trống")
        try {
            if (process?.isAlive != true) startLocked()
            val q = output ?: return@synchronized arrayOf("1", "Chưa khởi tạo shell")
            q.clear()
            counter += 1
            val marker = "__DK_SHELL_DONE_" + counter + "_" + System.nanoTime() + "__"
            // Commands are placed in a single session; return command exit status.
            writer?.apply {
                write(command)
                newLine()
                write("printf '\\n" + marker + ":%s\\n' " + 36.toChar() + "?")
                newLine()
                flush()
            }
            val text = StringBuilder()
            val deadline = System.nanoTime() + timeoutMs * 1000000L
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    closeLocked()
                    return@synchronized arrayOf("124", "Shell timeout; phiên đã được đóng")
                }
                val line = q.poll(remaining, TimeUnit.NANOSECONDS)
                if (line == null) {
                    closeLocked()
                    return@synchronized arrayOf("124", "Shell timeout; phiên đã được đóng")
                }
                if (line.startsWith(marker + ":")) {
                    return@synchronized arrayOf(line.substringAfter(':').trim(), text.toString().trim())
                }
                if (text.length < 16000) {
                    text.append(line.take((16000 - text.length).coerceAtLeast(0))).append('\n')
                }
            }
            @Suppress("UNREACHABLE_CODE")
            arrayOf("1", "Không xác định")
        } catch (e: Exception) {
            closeLocked()
            arrayOf("1", e.message ?: "Mất phiên shell")
        }
    }

    private fun startLocked() {
        closeLocked()
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val stream = p.inputStream.bufferedReader()
        val queue = LinkedBlockingQueue<String>(512)
        process = p
        writer = p.outputStream.bufferedWriter()
        output = queue
        Thread({
            try {
                stream.use {
                    it.forEachLine { line ->
                        if (!queue.offer(line)) {
                            queue.poll()
                            queue.offer(line)
                        }
                    }
                }
            } catch (_: Exception) { }
        }, "duykhai-shell-reader").apply { isDaemon = true; start() }
    }

    private fun closeLocked() {
        try { writer?.close() } catch (_: Exception) { }
        try { process?.destroyForcibly() } catch (_: Exception) { }
        writer = null
        process = null
        output = null
    }

    fun close() = synchronized(guard) { closeLocked() }
}

/**
 * No automatic privilege escalation. Only request() creates su.
 * status() is an in-memory, process-alive check, not a second su invocation.
 */
object RootSession {
    private val session = ShellSession(listOf("su"))
    @Volatile private var granted = false

    fun status(): Boolean = granted && session.isAlive()

    fun request(): Boolean {
        if (status()) return true
        val result = session.execute("id -u", 18000L)
        granted = result.getOrNull(0) == "0" && result.getOrNull(1)?.trim() == "0"
        if (!granted) session.close()
        return granted
    }

    fun command(shell: String): Array<String> {
        if (!status()) return arrayOf("1", "Root chưa được cấp hoặc phiên đã mất; bấm cấp quyền Root")
        val result = session.execute(shell, 12000L)
        if (!session.isAlive()) granted = false
        return result
    }

    fun invalidate() {
        granted = false
        session.close()
    }
}
