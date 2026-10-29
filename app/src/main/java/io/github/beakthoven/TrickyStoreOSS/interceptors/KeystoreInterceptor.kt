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
        if (KeyBoxUtils.hasKeyboxes()) {
            // LEAF_HACK mode: let the "get" transaction pass through to
            // the real keystore; onPostTransact will hack the returned
            // certificate.  This works because "get" returns a byte[] in
            // the reply Parcel, which onPostTransact can intercept.
            if (PkgConfig.needHack(callingUid) && code == getTransaction) {
                return Continue
            }

            // CRITICAL (Android 10-11 pitfall):
            // On Android 10/11, attestKey returns the certificate chain
            // via an async IKeystoreCertificateChainCallback.onFinished()
            // callback — NOT in the reply Parcel of the Binder transact.
            // Therefore onPostTransact cannot intercept the attestation
            // certificate chain for attestKey.
            //
            // In LEAF_HACK mode (teeBroken=false), we must still forge
            // the attestation certificate chain.  We do this by handling
            // attestKey in onPreTransact: the forged chain is signed with
            // the keybox's real TEE key (making attestationSecurityLevel
            // = TEE), and the boot-state extension fields (deviceLocked,
            // verifiedBootState, verifiedBootKey) are set to match the
            // forged values.  The callback receives the forged chain
            // directly; the real keystore is never called for attestKey.
            //
            // Without this, Momo detects TEE damage because the real
            // (unlocked) boot state leaks through the un-hacked
            // attestation certificate.
            val shouldHandle = PkgConfig.needHack(callingUid) || PkgConfig.needGenerate(callingUid)
            if (code == getTransaction && PkgConfig.needGenerate(callingUid)) {
                return Skip
            } else if (shouldHandle) {
                when (code) {
                    generateKeyTransaction -> {
                        val raw = runCatching {
                            data.enforceInterface(DESCRIPTOR)
                            val callback = IKeystoreKeyCharacteristicsCallback.Stub.asInterface(data.readStrongBinder())
                            val alias = data.readString()!!.extractAlias()
                            Log.i(TAG, "generateKeyTransaction uid $callingUid alias $alias")
                            val check = data.readInt()
                            val kma = KeymasterArguments()
                            val kgp = CertificateGen.KeyGenParameters()
                            if (check == 1) {
                                kma.readFromParcel(data)
                                kgp.algorithm = kma.getEnum(KeymasterDefs.KM_TAG_ALGORITHM, 0)
                                kgp.keySize = kma.getUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 0).toInt()
                                // kgp.setEcCurveName(kgp.keySize)
                                kgp.purpose = kma.getEnums(KeymasterDefs.KM_TAG_PURPOSE)
                                kgp.digest = kma.getEnums(KeymasterDefs.KM_TAG_DIGEST)
                                kgp.certificateNotBefore = kma.getDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME, Date())
                                if (kgp.algorithm == KeymasterDefs.KM_ALGORITHM_RSA) {
                                    try {
                                        val getArgumentByTag =
                                            KeymasterArguments::class.java.getDeclaredMethods().first {
                                                it.name == "getArgumentByTag"
                                            }
                                        getArgumentByTag.isAccessible = true
                                        val rsaArgument =
                                            getArgumentByTag.invoke(kma, KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT)

                                        val getLongTagValue =
                                            KeymasterArguments::class.java.getDeclaredMethods().first {
                                                it.name == "getLongTagValue"
                                            }
                                        getLongTagValue.isAccessible = true
                                        kgp.rsaPublicExponent = getLongTagValue.invoke(kma, rsaArgument) as BigInteger
                                    } catch (ex: Exception) {
                                        Log.e(TAG, "Read rsaPublicExponent error", ex)
                                    }
                                }
                                keyArguments[Key(callingUid, alias)] = kgp
                            }

                            val kc = KeyCharacteristics()
                            kc.swEnforced = KeymasterArguments()
                            kc.hwEnforced = kma

                            val ksr = createSuccessKeystoreResponse()
                            callback.onFinished(ksr, kc)

                            return createSuccessReply()
                        }
                        raw.onFailure { Log.e(TAG, "generateKeyTransaction error", it) }
                    }

                    getKeyCharacteristicsTransaction -> {
                        val raw = runCatching {
                            data.enforceInterface(DESCRIPTOR)
                            val callback = IKeystoreKeyCharacteristicsCallback.Stub.asInterface(data.readStrongBinder())
                            val alias = data.readString()!!.extractAlias()
                            Log.i(TAG, "getKeyCharacteristicsTransaction uid $callingUid alias $alias")
                            val kc = KeyCharacteristics()
                            val kma = KeymasterArguments()
                            kma.addEnum(
                                KeymasterDefs.KM_TAG_ALGORITHM,
                                keyArguments[Key(callingUid, alias)]!!.algorithm,
                            )
                            kc.swEnforced = KeymasterArguments()
                            kc.hwEnforced = kma

                            val ksr = createSuccessKeystoreResponse()
                            callback.onFinished(ksr, kc)

                            return createSuccessReply()
                        }
                        raw.onFailure { Log.e(TAG, "getKeyCharacteristicsTransaction error", it) }
                    }

                    exportKeyTransaction -> {
                        val raw = runCatching {
                            data.enforceInterface(DESCRIPTOR)
                            val callback = IKeystoreExportKeyCallback.Stub.asInterface(data.readStrongBinder())
                            val alias = data.readString()!!.extractAlias()
                            Log.i(TAG, "exportKeyTransaction uid $callingUid alias $alias")
                            val kp = CertificateGen.generateKeyPair(keyArguments[Key(callingUid, alias)]!!)
                            keyPairs[Key(callingUid, alias)] = kp!!

                            val erP = Parcel.obtain()
                            erP.writeInt(KeyStore.NO_ERROR)
                            erP.writeByteArray(kp.public.encoded)
                            erP.setDataPosition(0)
                            val er = ExportResult.CREATOR.createFromParcel(erP)
                            erP.recycle()

                            callback.onFinished(er)

                            return createSuccessReply()
                        }
                        raw.onFailure { Log.e(TAG, "exportKeyTransaction error", it) }
                    }

                    attestKeyTransaction -> {
                        val raw = runCatching {
                            data.enforceInterface(DESCRIPTOR)
                            val callback = IKeystoreCertificateChainCallback.Stub.asInterface(data.readStrongBinder())
                            val alias = data.readString()!!.extractAlias()
                            Log.i(TAG, "attestKeyTransaction uid $callingUid alias $alias")
                            val check = data.readInt()
                            val kma = KeymasterArguments()
                            if (check == 1) {
                                kma.readFromParcel(data)
                                val attestationChallenge =
                                    kma.getBytes(KeymasterDefs.KM_TAG_ATTESTATION_CHALLENGE, ByteArray(0))

                                val ksr = createSuccessKeystoreResponse()

                                val key = Key(callingUid, alias)
                                val ka = keyArguments[key]!!
                                ka.attestationChallenge = attestationChallenge
                                val chain = CertificateGen.generateChain(callingUid, ka, keyPairs[key]!!)

                                val kcc = KeymasterCertificateChain(chain)
                                callback.onFinished(ksr, kcc)
                            }

                            return createSuccessReply()
                        }
                        raw.onFailure { Log.e(TAG, "attestKeyTransaction error", it) }
                    }
                }
            }
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
        // [v3.1.1-ckb1 升级保留] LEAF_HACK 门——只对目标包 hack post-transact
        if (!PkgConfig.needHack(callingUid)) return Skip
        Logger.d(
            "intercept post $target uid=$callingUid pid=$callingPid dataSz=${data.dataSize()} replySz=${reply.dataSize()}",

        )
        try {
            data.enforceInterface(DESCRIPTOR)
            val alias = data.readString() ?: ""
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
        } catch (t: Throwable) {
            Logger.e("failed to hack certificate chain of uid=$callingUid pid=$callingPid!", t)
        }
        return Skip
    }
}
