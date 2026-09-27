package io.github.xtratter.droidtop

import java.io.File
import java.io.IOException

/**
 * Постоянно запущенная оболочка: `sh` (права приложения) или `su` (root).
 * Команды пишем в stdin, ответ читаем из stdout до строки-маркера.
 */
class Shell private constructor(private val proc: Process, val root: Boolean) {
    private val input = proc.outputStream.bufferedWriter()
    private val output = proc.inputStream.bufferedReader()
    private var seq = 0

    /** Выполнить команду и вернуть её вывод; null — оболочка умерла. */
    @Synchronized
    fun run(cmd: String): String? {
        val mark = "__DROIDTOP_${++seq}__"
        return try {
            input.write(cmd)
            input.write("\nprintf '\\n%s\\n' $mark\n")
            input.flush()
            val sb = StringBuilder()
            while (true) {
                val line = output.readLine() ?: run { close(); return null }
                if (line == mark) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (e: IOException) {
            close()
            null
        }
    }

    fun close() {
        try { proc.destroy() } catch (_: Exception) {}
    }

    companion object {
        /** Открыть оболочку; для root проверяем, что `id -u` действительно 0. */
        fun open(root: Boolean): Shell? = try {
            val pb = ProcessBuilder(if (root) "su" else "sh")
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            val sh = Shell(pb.start(), root)
            val id = sh.run("exec 2>/dev/null; id -u")?.trim()
            when {
                id == null -> null
                root && id != "0" -> { sh.close(); null }
                else -> sh
            }
        } catch (e: IOException) {
            null
        }
    }
}
