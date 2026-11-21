/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.logging

import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TrickyStoreOSS"

object Logger {
    // [2026-11-20 日志修盲] 文件环形日志只在 daemon 进程启用（Main.kt main() 第一时间置
    // true）。注入侧跑在 keystore 进程里，共享同一个 Logger：置位前绝不写文件——keystore
    // 域写 /data/adb 会被 SELinux 拒绝，其 android.util.Log 走 logcat 即可。
    @Volatile var enableFileSink: Boolean = false

    fun v(msg: String, tr: Throwable? = null) = log(Log.VERBOSE, msg, tr)

    fun d(msg: String, tr: Throwable? = null) = log(Log.DEBUG, msg, tr)

    fun i(msg: String, tr: Throwable? = null) = log(Log.INFO, msg, tr)

    fun w(msg: String, tr: Throwable? = null) = log(Log.WARN, msg, tr)

    fun e(msg: String, tr: Throwable? = null) = log(Log.ERROR, msg, tr)

    // —— logd 就绪缓存：至多每 10s 重查一次 ——
    // 旧版 lazy 一次性判定：daemon 早于 logd 启动时缓存 false → 整个 boot 的日志全丢
    // （MI9 开机注入实录）。改为带时间戳缓存，logd 就绪后 10s 内自然开始输出。
    private const val LOGD_RECHECK_INTERVAL_MS = 10_000L

    // —— 文件环形日志（仅 daemon）——
    private const val LOG_DIR_PATH = "/data/adb/tricky_store/logs"
    private const val LOG_FILE_NAME = "ts_daemon.log"
    private const val MAX_LOG_FILE_BYTES = 256L * 1024
    private const val FILE_RETRY_INTERVAL_MS = 60_000L // 失败后至多每分钟重试一次，不刷屏

    private val logFile = File(LOG_DIR_PATH, LOG_FILE_NAME)
    private val fileLock = Any()
    // [v3.1.4-ckb1 O6] 缓冲批量刷：每行三系统调用(open/write/close)+stat → 8KB 常驻缓冲，
    // 每 64 行 flush；崩溃丢 ≤64 行（文件日志为补充通道，可接受）
    private const val FLUSH_LINES = 64
    private var bufferedLines = 0
    private var sinkStream: java.io.FileOutputStream? = null
    // SimpleDateFormat 非线程安全：只在 fileLock 锁内使用
    private val fileTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logdCheckedAtRealtime = Long.MIN_VALUE
    @Volatile private var logdReadyCached = false
    @Volatile private var fileSinkReady = false
    @Volatile private var fileSinkFailed = false
    @Volatile private var lastFileAttemptRealtime = Long.MIN_VALUE

    private fun log(priority: Int, msg: String, tr: Throwable?) {
        val line = if (tr == null) msg else "$msg\n${Log.getStackTraceString(tr)}"
        // 文件通道独立于 logd 状态：早开机 logd 未就绪的行恰恰是最容易丢的现场
        if (logdReady()) Log.println(priority, TAG, line)
        appendToFile(priority, line)
    }

    private fun logdReady(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val checkedAt = logdCheckedAtRealtime
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < LOGD_RECHECK_INTERVAL_MS) return logdReadyCached
        val ready = SystemProperties.get("init.svc.logd", "") == "running"
        logdCheckedAtRealtime = now
        logdReadyCached = ready
        return ready
    }

    private fun appendToFile(priority: Int, msg: String) {
        if (!enableFileSink) return
        val file = sinkFile() ?: return
        synchronized(fileLock) {
            try {
                val stamp = fileTimeFormat.format(Date())
                if (file.length() > MAX_LOG_FILE_BYTES) rotateLocked(file)
                var out = sinkStream
                if (out == null) {
                    out = FileOutputStream(file, true)
                    sinkStream = out
                }
                out.write("[$stamp ${priorityLetter(priority)}] $msg\n".toByteArray(Charsets.UTF_8))
                bufferedLines++
                if (bufferedLines >= FLUSH_LINES) {
                    out.flush()
                    bufferedLines = 0
                }
            } catch (t: Throwable) {
                // 文件日志是补充通道，写失败静默放弃，绝不影响注入主链路；
                // 流损坏时丢弃句柄，下次写重建
                try { sinkStream?.close() } catch (_: Throwable) {}
                sinkStream = null
                bufferedLines = 0
            }
        }
    }

    // 目录创建只做一次；失败置 failed 并按 FILE_RETRY_INTERVAL_MS 节流重试，不刷屏不崩溃
    private fun sinkFile(): File? {
        if (fileSinkReady) return logFile
        synchronized(fileLock) {
            if (fileSinkReady) return logFile
            val now = SystemClock.elapsedRealtime()
            if (fileSinkFailed && now - lastFileAttemptRealtime < FILE_RETRY_INTERVAL_MS) return null
            lastFileAttemptRealtime = now
            val dir = logFile.parentFile
            fileSinkReady = dir != null && (dir.isDirectory || dir.mkdirs())
            fileSinkFailed = !fileSinkReady
            return if (fileSinkReady) logFile else null
        }
    }

    // 只保留一份 .1 旧档；rotate 前把历史残留（.1/.2/...）一并清掉
    private fun rotateLocked(file: File) {
        // O6: 轮转前先关并复位缓冲句柄（旧句柄指向已改名文件；rename 期间不应有写句柄）
        try { sinkStream?.flush() } catch (_: Throwable) {}
        try { sinkStream?.close() } catch (_: Throwable) {}
        sinkStream = null
        bufferedLines = 0
        val dir = file.parentFile ?: return
        dir.listFiles { f -> f.name.startsWith("${file.name}.") }?.forEach { it.delete() }
        if (!file.renameTo(File(dir, "${file.name}.1"))) file.delete()
    }

    private fun priorityLetter(priority: Int): Char =
        when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> '?'
        }
}
