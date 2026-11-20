/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.security.Credentials
import android.security.keystore.IKeystoreService
import io.github.beakthoven.TrickyStoreOSS.CertificateHack
import io.github.beakthoven.TrickyStoreOSS.KeyBoxUtils
import io.github.beakthoven.TrickyStoreOSS.config.PkgConfig
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.createByteArrayReply
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.getTransactCode
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.hasException
import io.github.beakthoven.TrickyStoreOSS.logging.Logger

@SuppressLint("BlockedPrivateApi")
object KeystoreInterceptor : BaseKeystoreInterceptor() {
    private val getTransaction = getTransactCode(IKeystoreService.Stub::class.java, "get")

    override val interceptedCodes: IntArray by lazy {
        intArrayOf(getTransaction).filter { it >= 0 }.toIntArray()
    }

    override val serviceName = "android.security.keystore"
    override val processName = "keystore"
    override val injectionCommand = "exec ./inject `pidof keystore` libTrickyStoreOSS.so entry"

    private const val DESCRIPTOR = "android.security.keystore.IKeystoreService"

    private fun String.extractLegacyAlias(): String =
        sequenceOf(
            Credentials.USER_PRIVATE_KEY,
            Credentials.USER_SECRET_KEY,
            Credentials.USER_CERTIFICATE,
            Credentials.CA_CERTIFICATE,
        ).firstNotNullOfOrNull { prefix ->
            if (startsWith(prefix) && length > prefix.length) substring(prefix.length) else null
        } ?: this

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        // [2026-11-20 日志修盲] legacy 路径补 per-query 日志（与 keystore2 路径同位点、同
        // 措辞风格）。决策与短路语义保持原样：hasKeyboxes → 事务码 → needHack，仅加日志。
        if (code != getTransaction) return Skip
        if (!KeyBoxUtils.hasKeyboxes()) {
            Logger.d("intercept pre get uid=$callingUid pid=$callingPid; SKIP no-keybox-loaded")
            return Skip
        }
        if (!PkgConfig.needHack(callingUid)) {
            Logger.d("intercept pre get uid=$callingUid pid=$callingPid; SKIP not-in-target")
            return Skip
        }
        Logger.d("intercept pre get uid=$callingUid pid=$callingPid dataSz=${data.dataSize()}; SERVE LEAF_HACK")
        return Continue
    }

    override fun onPostTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int,
    ): Result {
        if (target != keystore || code != getTransaction || reply == null) return Skip
        if (reply.hasException()) return Skip
        Logger.d(
            "intercept post $target uid=$callingUid pid=$callingPid dataSz=${data.dataSize()} replySz=${reply.dataSize()}"
        )
        try {
            data.enforceInterface(DESCRIPTOR)
            val alias = data.readString() ?: ""
            Logger.d("get post-hook: uid=$callingUid alias=$alias")
            var response = reply.createByteArray()
            when {
                alias.startsWith(Credentials.USER_CERTIFICATE) &&
                    alias.length > Credentials.USER_CERTIFICATE.length -> {
                    response = CertificateHack.hackUserCertificate(response!!, alias.extractLegacyAlias(), callingUid)
                    Logger.i("Hacked leaf certificate for uid=$callingUid")
                    return createByteArrayReply(response)
                }
                alias.startsWith(Credentials.CA_CERTIFICATE) &&
                    alias.length > Credentials.CA_CERTIFICATE.length -> {
                    response = CertificateHack.hackCACertificateChain(response!!, alias.extractLegacyAlias(), callingUid)
                    Logger.i("Hacked CA certificate chain for uid=$callingUid")
                    return createByteArrayReply(response)
                }
            }
            Logger.d("get post-hook: non-certificate alias, forwarding unpatched uid=$callingUid alias=$alias")
        } catch (t: Throwable) {
            Logger.e(
                "failed to hack certificate chain of uid=$callingUid pid=$callingPid; " +
                    "fell back to original response",
                t,
            )
        }
        return Skip
    }
}
