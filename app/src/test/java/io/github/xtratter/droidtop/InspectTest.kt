package io.github.xtratter.droidtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InspectTest {
    @Test
    fun parsesThreadStatWithSpacesInName() {
        val t = Inspect.parseThread("1234 (Binder:5 (x) y) S 1 777 0 0 -1 0 0 0 0 0 150 50 0 0 10 -10 42 0 " +
            "900 5000 2500 0 0 0 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0")!!
        assertEquals(1234, t.tid)
        assertEquals("Binder:5 (x) y", t.name)
        assertEquals('S', t.state)
        assertEquals(200L, t.ticks)
        assertEquals(-10, t.nice)
        assertEquals(3, t.core)
        assertNull(Inspect.parseThread("garbage"))
    }

    @Test
    fun cpuFromTickDelta() {
        val t = Inspect.parseThread("7 (w) R 1 7 0 0 -1 0 0 0 0 0 300 100 0 0 20 0 1 0 " +
            "5 1000 300 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0")!!
        val fresh = Inspect.parseThread("8 (new) S 1 7 0 0 -1 0 0 0 0 0 10 0 0 0 20 0 1 0 " +
            "5 1000 300 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0")!!
        Inspect.applyCpu(listOf(t, fresh), mapOf(7 to 300L), 2.0, 100)
        assertEquals(50f, t.cpu, 0.01f)      // 100 тиков за 2 с при 100 Гц — половина ядра
        assertTrue(fresh.cpu.isNaN())       // новый поток: сравнивать не с чем
    }

    @Test
    fun readsRealThreadsOfThisProcess() {
        val lines = File("/proc/self/task").listFiles()!!.mapNotNull {
            runCatching { File(it, "stat").readText() }.getOrNull()
        }
        val threads = Inspect.parseThreads(lines.joinToString(""))
        assertTrue(threads.isNotEmpty())
        assertTrue(threads.all { it.tid > 0 && it.core >= 0 })
    }

    @Test
    fun parsesLsOutputAndResolvesSockets() {
        val out = """
            |total 0
            |lrwx------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 0 -> /dev/null
            |lrwx------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 12 -> socket:[5001]
            |lr-x------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 3 -> /data/app/x/base.apk
            |l-wx------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 4 -> pipe:[777]
            |lrwx------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 5 -> anon_inode:[eventfd]
            |lrwx------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 6 -> socket:[5002]
            |lrwx------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 7 -> socket:[5003]
            |lrwx------ 1 u0_a1 u0_a1 64 2026-09-28 10:00 8 -> socket:[9999]
            |@
            |/proc/1/net/tcp:  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
            |/proc/1/net/tcp:   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 5001 1 0
            |/proc/1/net/tcp6:   0: 0000000000000000FFFF00000501A8C0:C350 0000000000000000FFFF000008080808:01BB 01 00000000:00000000 00:00000000 00000000  1000        0 5002 1 0
            |/proc/1/net/unix:Num       RefCount Protocol Flags    Type St Inode Path
            |/proc/1/net/unix:0000000000000000: 00000002 00000000 00010000 0001 01 5003 /dev/socket/zygote
            |""".trimMargin()
        val fds = Inspect.parseFiles(out)
        assertEquals(listOf(0, 3, 4, 5, 6, 7, 8, 12), fds.map { it.fd })
        val byFd = fds.associateBy { it.fd }
        assertEquals(Inspect.Kind.DEVICE, byFd[0]!!.kind)
        assertEquals(Inspect.Kind.FILE, byFd[3]!!.kind)
        assertEquals(Inspect.Kind.PIPE, byFd[4]!!.kind)
        assertEquals(Inspect.Kind.OTHER, byFd[5]!!.kind)
        assertEquals("TCP 127.0.0.1:8080 LISTEN", byFd[12]!!.socket)
        assertEquals("TCP 192.168.1.5:50000 → 8.8.8.8:443 ESTABLISHED", byFd[6]!!.socket)
        assertEquals("unix /dev/socket/zygote", byFd[7]!!.socket)
        assertNull(byFd[8]!!.socket)            // нет в таблицах — покажем socket:[inode]
    }

    @Test
    fun formatsAddresses() {
        assertEquals("127.0.0.1:8080", Inspect.addr("0100007F:1F90"))
        assertEquals("192.168.1.5:50000", Inspect.addr("0000000000000000FFFF00000501A8C0:C350"))
        assertEquals("[::]:443", Inspect.addr("00000000000000000000000000000000:01BB"))
        assertEquals("[::1]:53", Inspect.addr("00000000000000000000000001000000:0035"))
        // 2001:db8::1 — каждое 32-битное слово записано младшим байтом вперёд
        assertEquals("[2001:db8::1]:80", Inspect.addr("B80D0120000000000000000001000000:0050"))
        assertNull(Inspect.addr("xyz"))
    }
}
