/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import android.os.Build
import io.github.beakthoven.TrickyStoreOSS.config.PkgConfig
import io.github.beakthoven.TrickyStoreOSS.interceptors.Keystore2Interceptor
import io.github.beakthoven.TrickyStoreOSS.interceptors.KeystoreInterceptor
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import java.security.Security
import kotlin.system.exitProcess
import org.bouncycastle.jce.provider.BouncyCastleProvider

private const val RETRY_DELAY_MS = 1000L

fun main(args: Array<String>) {
    Logger.i("Welcome to TrickyStoreOSS!")
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.addProvider(BouncyCastleProvider())

    try {
        val probe = AttestUtils.probe()
        Logger.i("Startup attestation probe: ${probe.status} (${probe.failureCategory ?: "none"})")
        AndroidUtils.setupBootHash()
        waitForHealthyKeystore()
        initializeInterceptors()
        maintainService()
    } catch (e: Exception) {
        Logger.e("Fatal error in main", e)
        throw e
    }
}

private const val MAX_INIT_RETRIES = 60

// [v3.1.1-ckb1 互锁修复] 注入前确认 keystore 服务能响应 binder（带超时）。
// 半死状态（wchan=skb_wait 单线程）下注入会让全体 App 的 keystore 调用阻塞
// 60s 超时（settings FinalizerWatchdog 实证 2026-10-30），进而拖死系统。
private fun waitForHealthyKeystore() {
    var waited = 0
    while (waited < 120) {
        val healthy = try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            true
        } catch (e: Exception) {
            Logger.w("keystore not healthy yet (${e.javaClass.simpleName}); waiting")
            false
        }
        if (healthy) return
        Thread.sleep(1000)
        waited++
    }
    Logger.e("keystore still unhealthy after 120s; injecting anyway")
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
