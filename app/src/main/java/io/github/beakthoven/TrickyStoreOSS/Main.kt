/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import android.os.Build
import android.os.SystemClock
import android.os.SystemProperties
import io.github.beakthoven.TrickyStoreOSS.config.PkgConfig
import io.github.beakthoven.TrickyStoreOSS.interceptors.Keystore2Interceptor
import io.github.beakthoven.TrickyStoreOSS.interceptors.KeystoreInterceptor
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import java.io.File
import java.security.Security
import kotlin.system.exitProcess
import org.bouncycastle.jce.provider.BouncyCastleProvider

private const val RETRY_DELAY_MS = 1000L

fun main(args: Array<String>) {
    // 仅 daemon 进程启用文件环形日志；注入侧（keystore 进程）共享同一 Logger，保持 logcat
    Logger.enableFileSink = true
    Logger.i("Welcome to TrickyStoreOSS!")
    logStartupBanner()
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.addProvider(BouncyCastleProvider())

    try {
        // [v3.1.2-ckb1 时序优化] probe 与健康门并行。约束不变：probe 结果必须在拦截器
        // 上线前就绪（首次拦截查询若同步触发 probe，会把 keystore2 的 transact 卡在
        // probe 等待上）；probe 里的 AAID 服务等待（≤120s）不再串行叠加在健康门之前，
        // 典型总耗时从 sum(probe, gate) 变 max(probe, gate)。
        val probeThread = Thread({ AttestUtils.probe() }, "tss-attest-probe").apply { start() }
        AndroidUtils.setupBootHash()
        waitForHealthyKeystore()
        probeThread.join()
        Logger.i(
            "Startup attestation probe done: ${AttestUtils.probeResult.status} " +
                "(${AttestUtils.probeResult.failureCategory ?: "none"})",
        )
        initializeInterceptors()
        maintainService()
    } catch (e: Exception) {
        Logger.e("Fatal error in main", e)
        throw e
    }
}

private const val CONFIG_DIR = "/data/adb/tricky_store"
private val keyboxDeviceIdRegex = Regex("DeviceID\\s*=\\s*\"([^\"]*)\"")
private val keyAlgorithmRegex = Regex("<Key\\b[^>]*\\balgorithm\\s*=\\s*\"([^\"]*)\"")

// [2026-11-20 日志修盲] 开机自标识横幅：模块版本/系统/拦截器形态/目标数/keybox 身份在
// Welcome 后立刻打全——跨模块升级的 boot 日志不再歧义。全部轻量读取（module.prop 版本行
// + keybox.xml 属性正则 + target.txt 行数统计），不做重解析：正式加载仍由
// PkgConfig.initialize() 完成（KeyBoxUtils 带快照保护的完整解析不受影响）。
private fun logStartupBanner() {
    val flavor =
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) "legacy-KeystoreInterceptor"
        else "Keystore2Interceptor"
    Logger.i(
        "module=${moduleVersion()} android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) " +
            "interceptor=$flavor targetPackages=${targetPackageCountSummary()}",
    )
    Logger.i(keyboxIdentitySummary())
}

// daemon cwd = 模块目录（./inject 相对路径依赖同一 cwd），module.prop 就在手边；
// 读不到时按顺序兜底 classpath 同目录与标准 Magisk 模块路径，再不行如实报 unknown
private fun moduleVersion(): String {
    val candidates = mutableListOf(File("module.prop"))
    System.getProperty("java.class.path")
        ?.let { cp -> File(cp).absoluteFile.parentFile }
        ?.let { candidates.add(File(it, "module.prop")) }
    candidates.add(File("/data/adb/modules/tricky_store/module.prop"))
    for (file in candidates) {
        if (!file.isFile) continue
        val version =
            runCatching {
                file.readLines().firstOrNull { it.startsWith("version=") }?.removePrefix("version=")?.trim()
            }.getOrNull()
        if (!version.isNullOrEmpty()) return version
    }
    return "unknown"
}

// 只数非空非注释行（含 !/? 模式后缀，与 target.txt 语义一致）；文件缺失报 missing
private fun targetPackageCountSummary(): String =
    runCatching {
        File(CONFIG_DIR, "target.txt").readLines().count { line -> line.substringBefore('#').trim().isNotEmpty() }
    }.getOrNull()?.toString() ?: "missing"

// 轻量身份提取：DeviceID / Key.algorithm 属性正则直读 keybox.xml；文件缺失或无条目时
// 如实上报，不抛错
private fun keyboxIdentitySummary(): String {
    val file = File(CONFIG_DIR, "keybox.xml")
    val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        ?: return "keybox: none loaded (${file.path} not found)"
    val deviceIds =
        keyboxDeviceIdRegex.findAll(text).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
    val algorithms =
        keyAlgorithmRegex.findAll(text).map { it.groupValues[1] }.filter { it.isNotBlank() }.distinct().toList()
    val devicePart = if (deviceIds.isEmpty()) "<none found>" else deviceIds.joinToString(", ")
    return "keybox: DeviceID=[$devicePart] algorithms=[${algorithms.joinToString(", ")}]"
}

private const val MAX_INIT_RETRIES = 60

// —— 健康门参数（系统属性可覆盖，现场调参免重编译）——
private const val HEALTH_POLL_INTERVAL_MS = 1000L
private const val HEALTH_CAP_MS_DEFAULT = 60_000L // 旧版固定烧满 120s；超时终态语义不变：超时后照样注入
private const val PID_STABLE_MS_DEFAULT = 10_000L // 同一 keystore PID 连续存活窗（过滤 keystore 崩溃重启循环）
private const val KEYSTORE_LOAD_PROBE_TIMEOUT_MS = 1500L

private fun propOverrideMs(prop: String, defaultMs: Long): Long =
    SystemProperties.get(prop, "").toLongOrNull()?.takeIf { it > 0 } ?: defaultMs

// [v3.1.2-ckb1 时序优化] 多信号条件门，替代 v3.1.1 的固定 120s 轮询烧满。
// 旧版问题：KeyStore.load 在本 daemon 上下文（app_process 无 App Context）里，
// MIUI A11 legacy keystore（恒单线程态，207 号档 §十三：设备特性非故障）上恒失败
// → 每次开机纯死等 120s 后 "injecting anyway"——门实际是延时器不是检测器。
// 新门语义（保护不弱于旧版，均以 boot 完成为前提）：
//   1. 最快路径：KeyStore.load 成功（keystore 真实可服务）→ 立即放行；
//   2. 条件路径：sys.boot_completed=1 + init.svc.keystore2|keystore=running +
//      同一 keystore PID 连续存活 ≥pid_stable_ms → 放行。崩溃重启循环中的半死
//      keystore 攒不满 PID 稳定窗（对应历史互锁事故的病灶），不会提前误放行；
//   3. 兜底：health_cap_ms 超时 → 与旧版一致 "injecting anyway"。
private fun waitForHealthyKeystore() {
    val startedAt = SystemClock.elapsedRealtime()
    val capMs = propOverrideMs("persist.tsoss.health_cap_ms", HEALTH_CAP_MS_DEFAULT)
    val stableMs = propOverrideMs("persist.tsoss.pid_stable_ms", PID_STABLE_MS_DEFAULT)
    val targetProcess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "keystore2" else "keystore"
    var lastPid = ""
    var stableSince = -1L
    var poll = 0
    while (true) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed >= capMs) {
            Logger.e("keystore health gate cap ${capMs}ms reached; injecting anyway")
            return
        }
        poll++
        val pid = pidOf(targetProcess)
        val svc = keystoreSvcState()
        val bootCompleted = SystemProperties.get("sys.boot_completed", "") == "1"
        if (bootCompleted && svc == "running" && pid.isNotEmpty()) {
            if (keyStoreLoadHealthy()) {
                Logger.i("keystore healthy (KeyStore.load ok) after ${elapsed}ms; proceeding")
                return
            }
            if (pid == lastPid) {
                if (stableSince < 0) stableSince = SystemClock.elapsedRealtime()
                val stableFor = SystemClock.elapsedRealtime() - stableSince
                if (stableFor >= stableMs) {
                    Logger.i(
                        "keystore stable: svc=running pid=$pid alive ${stableFor}ms " +
                            "(KeyStore.load not passing; known-benign legacy/daemon-context state), " +
                            "gate ${elapsed}ms; proceeding",
                    )
                    return
                }
                Logger.d("gate poll #$poll: boot=1 svc=$svc pid=$pid stableFor=${stableFor}ms load=fail; waiting")
            } else {
                lastPid = pid
                stableSince = SystemClock.elapsedRealtime()
                Logger.d("gate poll #$poll: boot=1 svc=$svc pid=$pid (new pid, stability window restarted); waiting")
            }
        } else {
            lastPid = ""
            stableSince = -1L
            Logger.d("gate poll #$poll: boot=$bootCompleted svc=$svc pid=$pid; waiting")
        }
        Thread.sleep(HEALTH_POLL_INTERVAL_MS)
    }
}

private fun pidOf(process: String): String = try {
    Runtime.getRuntime().exec(arrayOf("pidof", process))
        .inputStream.bufferedReader().readText().trim()
} catch (e: Exception) {
    ""
}

// 双命名：MI9 MIUI-A11 服务名 = keystore，A13+/标准 AOSP = keystore2（217 号档）
private fun keystoreSvcState(): String {
    val k2 = SystemProperties.get("init.svc.keystore2", "")
    if (k2.isNotEmpty()) return k2
    return SystemProperties.get("init.svc.keystore", "")
}

// load 探测放辅助线程并限时 join：半死 keystore 上一次无超时 binder 调用即可把
// 整个门卡死（旧版在主线程直跑 load 的隐患）。探测线程若阻塞超时则放弃等待，
// 该线程随 daemon 生命周期搁置（daemon 本身常驻 join，不新增退出路径）。
private fun keyStoreLoadHealthy(): Boolean {
    var ok = false
    val t = Thread {
        ok = try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            true
        } catch (e: Throwable) {
            false
        }
    }
    t.start()
    t.join(KEYSTORE_LOAD_PROBE_TIMEOUT_MS)
    if (t.isAlive) {
        Logger.w("KeyStore.load probe blocked >${KEYSTORE_LOAD_PROBE_TIMEOUT_MS}ms; treating as unhealthy")
    }
    return ok
}

private fun initializeInterceptors() {
    val interceptor = selectKeystoreInterceptor()

    var retries = 0
    while (!interceptor.tryRunKeystoreInterceptor()) {
        if (++retries >= MAX_INIT_RETRIES) {
            Logger.e("interceptor init failed $retries times; exiting daemon to avoid keystore wedge")
            exitProcess(1)
        }
        Logger.d("Retrying interceptor initialization ($retries/$MAX_INIT_RETRIES)...")
        Thread.sleep(RETRY_DELAY_MS * (1 + retries / 10))
    }

    PkgConfig.initialize()
    Logger.i("Interceptors initialized successfully")
}

private fun selectKeystoreInterceptor() =
    when {
        Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R -> {
            Logger.i("Using KeystoreInterceptor for Android Q/R (SDK ${Build.VERSION.SDK_INT})")
            KeystoreInterceptor
        }
        else -> {
            Logger.i("Using Keystore2Interceptor for Android S+ (SDK ${Build.VERSION.SDK_INT})")
            Keystore2Interceptor
        }
    }

private fun maintainService() {
    Logger.i("Service started, entering maintenance mode")
    Thread.currentThread().join()
}
