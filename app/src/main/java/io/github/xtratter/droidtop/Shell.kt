package io.github.xtratter.droidtop

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException

/** Откуда берём права: обычное приложение, Shizuku (права ADB) или root. */
enum class Access { USER, SHIZUKU, ROOT }

/**
 * Постоянно запущенная оболочка: `sh` с правами приложения, `sh` через Shizuku или `su`.
 * Команды пишем в stdin, ответ читаем из stdout до строки-маркера.
 */
class Shell private constructor(private val proc: Process, val access: Access) {
    private val input = proc.outputStream.bufferedWriter()
    private val output = proc.inputStream.bufferedReader()
    private var seq = 0

    /** Выполнить команду и вернуть её вывод; null — оболочка умерла. */
    fun run(cmd: String): String? = runLines(cmd)?.joinToString("") { it + "\n" }

    /** То же, но построчно — без склейки большого вывода в одну строку и повторной нарезки. */
    @Synchronized
    fun runLines(cmd: String): List<String>? {
        val mark = "__DROIDTOP_${++seq}__"
        return try {
            input.write(cmd)
            input.write("\nprintf '\\n%s\\n' $mark\n")
            input.flush()
            val out = ArrayList<String>(1024)
            while (true) {
                val line = output.readLine() ?: run { close(); return null }
                if (line == mark) break
                out += line
            }
            out
        } catch (e: IOException) {
            close()
            null
        }
    }

    fun close() {
        try { proc.destroy() } catch (_: Exception) {}
    }

    companion object {
        fun open(access: Access): Shell? {
            val proc = try {
                when (access) {
                    Access.USER -> local("sh")
                    Access.ROOT -> local("su")
                    Access.SHIZUKU -> shizukuSh() ?: return null
                }
            } catch (e: Exception) {
                return null
            }
            val sh = Shell(proc, access)
            val id = sh.run("exec 2>/dev/null; id -u")?.trim()
            return when {
                id == null -> null
                access == Access.ROOT && id != "0" -> { sh.close(); null }
                else -> sh
            }
        }

        private fun local(cmd: String): Process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            .start()

        /** Shizuku запущен и разрешил нам доступ. */
        fun shizukuReady(): Boolean = try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        /**
         * Процесс `sh` от имени Shizuku (uid shell). В API 13 метод newProcess закрыт,
         * но по-прежнему работает — вызываем его через reflection, как и другие мониторы.
         */
        private fun shizukuSh(): Process? {
            // связь с Shizuku приходит асинхронно после старта приложения — подождём до 3 секунд
            repeat(30) { if (Shizuku.pingBinder()) return@repeat; Thread.sleep(100) }
            if (!shizukuReady()) return null
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            )
            m.isAccessible = true
            return m.invoke(null, arrayOf("sh"), null, null) as Process
        }
    }
}
