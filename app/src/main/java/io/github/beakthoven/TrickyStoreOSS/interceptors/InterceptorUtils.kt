/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.ServiceManager
import android.os.ServiceSpecificException
import android.security.KeyStore
import android.security.keystore.KeystoreResponse
import android.util.Log
import io.github.beakthoven.TrickyStoreOSS.logging.TAG
import kotlin.system.exitProcess

abstract class BaseKeystoreInterceptor : BinderInterceptor() {

    protected lateinit var keystore: IBinder
    protected var triedCount = 0
    protected var injected = false
    protected open val maxRetries: Int = 3

    protected abstract val serviceName: String
    protected abstract val injectionCommand: String
    protected abstract val processName: String

    fun tryRunKeystoreInterceptor(): Boolean {
        Log.i(TAG, "Trying to register ${this::class.simpleName} (attempt $triedCount)...")

        val service = getService() ?: return false
        val backdoor = getBinderBackdoor(service)

        return if (backdoor != null) {
            setupInterceptor(service, backdoor)
        } else {
            handleMissingBackdoor()
        }
    }

    protected open fun getService(): IBinder? = ServiceManager.getService(serviceName)

    protected open fun setupInterceptor(service: IBinder, backdoor: IBinder): Boolean {
        keystore = service
        Log.i(TAG, "Registering for $serviceName: $keystore")

        registerBinderInterceptor(backdoor, service, this)
        service.linkToDeath(createDeathRecipient(), 0)
        onInterceptorSetup(service, backdoor)

        return true
    }

    private fun handleMissingBackdoor(): Boolean {
        if (triedCount >= maxRetries) {
            // Don't exit — reset state and keep retrying.  At early boot
            // keystore2 may not be running or APatch's su may not be ready.
            // Exiting here used to kill the shell restart loop permanently
            // (via "|| exit 1" in post-fs-data.sh / service.sh), leaving
            // TrickyStoreOSS dead for the entire session.
            Log.w(TAG, "Backdoor still unavailable after $maxRetries attempts, resetting state for retry...")
            triedCount = 0
            injected = false
        }

        if (!injected) {
            if (performInjection()) {
                injected = true
            }
        }

        triedCount++
        return false
    }

    private val exactInjectMarker = java.io.File("./.apatch_exact_inject_v1").isFile
    private val suBin = java.io.File("/system/bin/su")

    private fun apatchInjectCapable(): Boolean {
        if (!suBin.canExecute()) return false
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(suBin.absolutePath, "--no-pty", "--inject-capable"))
            proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    protected open fun performInjection(): Boolean {
        val useExactInject = exactInjectMarker && apatchInjectCapable()

        // On APatch (exactInjectMarker present), if su is not yet ready at
        // early boot, do NOT fall back to legacy inject — it will fail
        // silently on APatch due to SELinux restrictions on ptrace.
        // Return false so the retry loop tries again once su is ready.
        if (exactInjectMarker && !useExactInject) {
            Log.i(TAG, "APatch exact inject marker present but su not ready, skipping injection (will retry)")
            return false
        }

        Log.i(TAG, "Attempting to inject into $processName (mode=${if (useExactInject) "apatch-exact" else "legacy"})...")

        val process = if (useExactInject) {
            val targetPid = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pidof $processName")).inputStream.bufferedReader().readText().trim()
            if (targetPid.isEmpty()) {
                Log.e(TAG, "$processName pid not found, will retry later")
                return false
            }
            val libName = if (processName == "keystore2") "libTrickyStoreOSS.so" else "libTrickyStoreOSS.so"
            val injectBin = java.io.File("./inject").absolutePath
            val libPath = java.io.File(".", libName).absolutePath
            Runtime.getRuntime().exec(arrayOf(
                suBin.absolutePath, "--no-pty", "-p",
                "--inject-target", targetPid,
                "--inject-library", libPath,
                "--", injectBin, targetPid, libPath, "entry"
            ))
        } else {
            val command = arrayOf("/system/bin/sh", "-c", injectionCommand)
            Log.d(TAG, "Injection command: ${command.joinToString(" ")}")
            Runtime.getRuntime().exec(command)
        }

        if (process.waitFor() != 0) {
            Log.e(TAG, "Injection failed, will retry...")
            return false
        }

        Log.i(TAG, "Injection completed successfully")
        return true
    }

    protected open fun createDeathRecipient(): IBinder.DeathRecipient =
        object : IBinder.DeathRecipient {
            override fun binderDied() {
                Log.d(TAG, "$serviceName died, daemon restarting")
                exitProcess(0)
            }
        }

    protected open fun onInterceptorSetup(service: IBinder, backdoor: IBinder) {
        // Default implementation does nothing
    }
}

object InterceptorUtils {

    fun getTransactCode(clazz: Class<*>, method: String): Int =
        clazz.getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }.getInt(null)

    fun createSuccessKeystoreResponse(): KeystoreResponse {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(KeyStore.NO_ERROR)
            parcel.writeString("")
            parcel.setDataPosition(0)
            return KeystoreResponse.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    fun createSuccessReply(resultCode: Int = KeyStore.NO_ERROR): BinderInterceptor.OverrideReply {
        val parcel = Parcel.obtain()
        parcel.writeNoException()
        parcel.writeInt(resultCode)
        return BinderInterceptor.OverrideReply(0, parcel)
    }

    fun createByteArrayReply(data: ByteArray, resultCode: Int = KeyStore.NO_ERROR): BinderInterceptor.OverrideReply {
        val parcel = Parcel.obtain()
        parcel.writeNoException()
        parcel.writeByteArray(data)
        return BinderInterceptor.OverrideReply(resultCode, parcel)
    }

    fun <T : Parcelable?> createTypedObjectReply(
        obj: T,
        flags: Int = 0,
        resultCode: Int = 0,
    ): BinderInterceptor.OverrideReply {
        val parcel = Parcel.obtain()
        parcel.writeNoException()
        parcel.writeTypedObject(obj, flags)
        return BinderInterceptor.OverrideReply(resultCode, parcel)
    }

    fun typedReply(metadata: Parcelable?): BinderInterceptor.OverrideReply = createTypedObjectReply(metadata)

    fun errorReply(errorCode: Int, message: String): BinderInterceptor.OverrideReply {
        val p = Parcel.obtain()
        p.writeException(ServiceSpecificException(errorCode, message))
        return BinderInterceptor.OverrideReply(0, p)
    }

    fun successReply(): BinderInterceptor.OverrideReply {
        val p = Parcel.obtain()
        p.writeNoException()
        return BinderInterceptor.OverrideReply(0, p)
    }

    fun String.extractAlias(): String {
        return when {
            contains("_") -> split("_")[1]
            else -> this
        }
    }

    fun Parcel.hasException(): Boolean {
        return runCatching { readException() }.exceptionOrNull() != null
    }
}
