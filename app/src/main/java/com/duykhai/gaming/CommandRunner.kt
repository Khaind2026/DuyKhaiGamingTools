package com.duykhai.gaming

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

object ShellProcess {
    fun execute(argv: List<String>): Array<String> {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            if (output.length < 16000) output.append(buffer, 0, minOf(count, 16000 - output.length))
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        reader.isDaemon = true
        reader.start()
        try {
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return arrayOf("124", "Hết thời gian chờ quyền/lệnh")
            }
            reader.join(1000)
            return arrayOf(process.exitValue().toString(), synchronized(output) { output.toString().trim() })
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

class ShellService : IShellService.Stub() {
    override fun execute(command: String): Array<String> =
        try { ShellProcess.execute(listOf("sh", "-c", command)) }
        catch (e: Exception) { arrayOf("1", e.message ?: "Shell error") }
    override fun destroy() { kotlin.system.exitProcess(0) }
}

object CommandRunner {
    @Volatile private var service: IShellService? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var binding = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShellService.Stub.asInterface(binder)
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; binding = false }
    }
    fun connect(context: Context) {
        if (binding || service != null) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            val options = Shizuku.UserServiceArgs(ComponentName(context, ShellService::class.java))
                .daemon(false).processNameSuffix("gaming_shell").version(3)
            args = options
            binding = true
            Shizuku.bindUserService(options, connection)
        } catch (_: Exception) { binding = false }
    }
    fun disconnect() {
        try { args?.let { Shizuku.unbindUserService(it, connection, true) } } catch (_: Exception) { }
        service = null
        binding = false
    }
    fun ready() = service?.asBinder()?.isBinderAlive == true
    fun run(command: String, root: Boolean): Array<String> = try {
        if (root) ShellProcess.execute(listOf("su", "-c", command))
        else service?.execute(command) ?: arrayOf("1", "Shizuku shell chưa sẵn sàng; cấp quyền rồi thử lại")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        arrayOf("1", "Đã hủy")
    } catch (e: Exception) { arrayOf("1", e.message ?: "Mất kết nối") }
}
