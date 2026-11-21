/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.Tag
import android.system.keystore2.Authorization
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import java.io.ByteArrayInputStream
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object CertificateHack {
    data class KeyIdentifier(val alias: String, val uid: Int)

    val leafAlgorithms = ConcurrentHashMap<KeyIdentifier, String>()

    // [v3.1.4-ckb1 O1] legacy 改签叶子缓存：键=uid+alias+输入叶子 SHA-256，值=改签后 DER。
    // 输入不变则输出确定不变（hackLeaf 全部输入可枚举），命中即省去每次的 ASN.1 重建+
    // BC 签名（每查询 1-5ms CPU——发热优化主项，222 号档 T1）。容量上限整体清空（真实
    // 键空间=uid×alias 组合，量级小；失效主路径是 invalidateLeafCache，上限只是保险丝）。
    private const val LEAF_CACHE_MAX = 128
    private data class LeafCacheKey(val uid: Int, val alias: String, val inputSha: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is LeafCacheKey && other.uid == uid && other.alias == alias &&
                other.inputSha.contentEquals(inputSha)
        override fun hashCode(): Int = uid * 31 + alias.hashCode() * 17 + inputSha.contentHashCode()
    }
    private val leafCache = ConcurrentHashMap<LeafCacheKey, ByteArray>()

    /** keybox/target/patchlevel 变更时由 Config.kt 调用（与 invalidatePatchedResponses 并排）。 */
    fun invalidateLeafCache() {
        leafCache.clear()
        Logger.d("leaf cache invalidated")
    }

    fun patchAuthorizations(authorizations: Array<Authorization>?, uid: Int): Array<Authorization>? {
        if (authorizations == null) return null
        val os = AndroidUtils.patchLevelOverride(uid)
        val vendor = AndroidUtils.vendorPatchLevelOverride(uid)
        val boot = AndroidUtils.bootPatchLevelOverride(uid)
        return authorizations
            .mapNotNull { auth ->
                val tag = auth.keyParameter.tag
                val override =
                    when (tag) {
                        Tag.OS_PATCHLEVEL -> os
                        Tag.VENDOR_PATCHLEVEL -> vendor
                        Tag.BOOT_PATCHLEVEL -> boot
                        else -> return@mapNotNull auth
                    }
                if (override == null) return@mapNotNull auth // device_default: keep the real value
                if (override == AndroidUtils.DO_NOT_REPORT) return@mapNotNull null // "no": omit
                Authorization().apply {
                    securityLevel = auth.securityLevel
                    keyParameter =
                        KeyParameter().apply {
                            this.tag = tag
                            this.value = KeyParameterValue.integer(override)
                        }
                }
            }
            .toTypedArray()
    }

    fun clearLeafAlgorithms() {
        leafAlgorithms.clear()
    }

    private fun hackLeaf(certificateChain: Array<Certificate>, uid: Int): Array<Certificate> {
        val leaf =
            CertificateUtils.certificateFactory.generateCertificate(ByteArrayInputStream(certificateChain[0].encoded))
                as X509Certificate
        val leafHolder = X509CertificateHolder(leaf.encoded)
        val extension = leafHolder.getExtension(ATTESTATION_OID) ?: return certificateChain
        val encodables = ASN1Sequence.getInstance(extension.extnValue.octets).toArray()
        // Some Android 11 Keymasters emit softwareEnforced/teeEnforced swapped.
        val teeIndex = if (containsRootOfTrust(encodables[6])) 6 else 7
        val teeEnforced = encodables[teeIndex] as ASN1Sequence

        var rootOfTrust: ASN1Encodable? = null
        val vector = ASN1EncodableVector()
        teeEnforced.forEach {
            (it as ASN1TaggedObject).let {
                if (it.tagNo == 704) rootOfTrust = it.baseObject.toASN1Primitive() else vector.add(it)
            }
        }

        val algorithm = leaf.publicKey.algorithm
        val keybox =
            KeyBoxUtils.keyboxes[algorithm]
                ?: KeyBoxUtils.keyboxes.entries.firstOrNull()?.value
                ?: throw UnsupportedOperationException("No keybox for algorithm: $algorithm")
        val certs = LinkedList(keybox.certificates)
        val builder =
            X509v3CertificateBuilder(
                X509CertificateHolder(certs[0].encoded).subject,
                leafHolder.serialNumber,
                leafHolder.notBefore,
                leafHolder.notAfter,
                leafHolder.subject,
                leafHolder.subjectPublicKeyInfo,
            )
        builder.addExtension(hackAttestExtension(rootOfTrust, vector, encodables, teeIndex, uid))
        leafHolder.extensions.extensionOIDs.forEach { oid ->
            // Skip stale AKI/SKID (2.5.29.35 / 2.5.29.14): the copied values still point at the
            // original keymaster issuer key, while the re-signed leaf is now issued by the keybox
            // leaf (whose certs carry their own SKIDs). Stale issuer-binding extensions fail
            // deep chain checks, so drop them and add no replacement AKI — matching the minimal
            // extension set of CertificateGen.buildCertificate (KeyUsage + attestation only).
            if (
                oid.id != ATTESTATION_OID.id &&
                oid.id != Extension.authorityKeyIdentifier.id &&
                oid.id != Extension.subjectKeyIdentifier.id
            ) {
                builder.addExtension(leafHolder.getExtension(oid))
            }
        }
        val leafDigest = leaf.sigAlgName.substringBefore("with", "SHA256")
        val keyboxSignerAlgo =
            when (keybox.keyPair.private) {
                is java.security.interfaces.ECPrivateKey -> "ECDSA"
                is java.security.interfaces.RSAPrivateKey -> "RSA"
                else ->
                    throw UnsupportedOperationException(
                        "Unsupported keybox signing key algorithm: ${keybox.keyPair.private.algorithm}"
                    )
            }
        certs.addFirst(
            JcaX509CertificateConverter()
                .getCertificate(
                    builder.build(
                        JcaContentSignerBuilder("${leafDigest}with$keyboxSignerAlgo").build(keybox.keyPair.private)
                    )
                )
        )
        return certs.toTypedArray()
    }

    fun hackCertificateChain(certificateChain: Array<Certificate>?, uid: Int): Array<Certificate> {
        if (certificateChain == null) throw UnsupportedOperationException("Certificate chain is null!")
        return try {
            hackLeaf(certificateChain, uid)
        } catch (t: Throwable) {
            Logger.e("Failed to hack certificate chain", t)
            certificateChain
        }
    }

    fun hackCACertificateChain(caList: ByteArray?, alias: String, uid: Int): ByteArray {
        if (caList == null) throw UnsupportedOperationException("CA list is null!")
        return try {
            val algorithm =
                leafAlgorithms.remove(KeyIdentifier(alias, uid))
                    ?: throw UnsupportedOperationException("No algorithm found for key ($alias, $uid)")
            val keybox =
                KeyBoxUtils.keyboxes[algorithm]
                    ?: KeyBoxUtils.keyboxes.entries.firstOrNull()?.value
                    ?: throw UnsupportedOperationException("Unsupported algorithm: $algorithm")
            CertificateUtils.run { keybox.certificates.toByteArray() } ?: caList
        } catch (t: Throwable) {
            Logger.e("Failed to hack CA certificate chain", t)
            caList
        }
    }

    fun hackUserCertificate(certificate: ByteArray?, alias: String, uid: Int): ByteArray {
        if (certificate == null) throw UnsupportedOperationException("Leaf certificate is null!")
        // [v3.1.4-ckb1 发热优化 O1] legacy 每查询重签缓存：与 keystore2 路径的
        // patchedResponses 对齐。键 = uid+alias+输入叶子哈希（输入不变则输出不变——
        // hackLeaf 的全部输入：原叶字段 + keybox + patchlevel 覆盖 + bootKey/digest）；
        // 失效 = invalidateLeafCache()（Config.kt 在 keybox/target/patchlevel 变更点调用，
        // 与 invalidatePatchedResponses 并排）。失败不缓存（保留重试语义）。
        val inputSha = try {
            java.security.MessageDigest.getInstance("SHA-256").digest(certificate)
        } catch (t: Throwable) {
            null
        }
        if (inputSha != null) {
            leafCache[LeafCacheKey(uid, alias, inputSha)]?.let { cached ->
                Logger.d("leaf cache hit uid=$uid alias=$alias")
                return cached
            }
        }
        return try {
            val leaf =
                CertificateUtils.certificateFactory.generateCertificate(ByteArrayInputStream(certificate))
                    as X509Certificate
            if (leaf.getExtensionValue(ATTESTATION_OID.id) == null) return certificate
            leafAlgorithms[KeyIdentifier(alias, uid)] = leaf.publicKey.algorithm
            val hacked = hackLeaf(arrayOf(leaf), uid)
            val encoded = hacked[0].encoded
            if (inputSha != null) {
                if (leafCache.size >= LEAF_CACHE_MAX) leafCache.clear()
                leafCache[LeafCacheKey(uid, alias, inputSha)] = encoded
            }
            encoded
        } catch (t: Throwable) {
            Logger.e("Failed to hack user certificate", t)
            certificate
        }
    }

    private fun hackAttestExtension(
        originalRootOfTrust: ASN1Encodable?,
        vector: ASN1EncodableVector,
        originalEncodables: Array<ASN1Encodable>,
        teeIndex: Int,
        uid: Int,
    ): Extension {
        val verifiedBootKey = AndroidUtils.bootKey
        // bootKey prefers ro.boot.vbmeta.digest; prefer the same property here so the served
        // verifiedBootKey/verifiedBootHash pair stays coherent with the device-visible digest
        // (avoids cert-vs-property contradiction flags). When the property is absent, keep the
        // original behavior below (original rootOfTrust hash, then the property fallback).
        var verifiedBootHash: ByteArray? = AndroidUtils.getBootHashFromProp()

        try {
            if (verifiedBootHash == null && originalRootOfTrust is ASN1Sequence) {
                verifiedBootHash = CertificateUtils.getByteArrayFromAsn1(originalRootOfTrust.getObjectAt(3))
            }
        } catch (t: Throwable) {
            Logger.e("Failed to get verified boot hash from original, using generated", t)
        }

        if (verifiedBootHash == null) {
            verifiedBootHash = AndroidUtils.getBootHashFromProp()
        }

        val rootOfTrustElements =
            arrayOf(
                DEROctetString(verifiedBootKey),
                ASN1Boolean.TRUE,
                ASN1Enumerated(0),
                DEROctetString(verifiedBootHash),
            )
        val hackedRootOfTrust = DERSequence(rootOfTrustElements)

        val osPatch = AndroidUtils.patchLevelOverride(uid)
        val vendorPatch = AndroidUtils.vendorPatchLevelOverride(uid)
        val bootPatch = AndroidUtils.bootPatchLevelOverride(uid)

        val rebuilt = mutableListOf<ASN1TaggedObject>()
        for (i in 0 until vector.size()) {
            val obj = vector.get(i)
            if (obj !is ASN1TaggedObject) continue
            // 704 (root of trust) and 705 (os version) are always rebuilt. A patch level is
            // stripped only when overridden or omitted; device_default (null) keeps the
            // original, correctly-sourced value (e.g. boot from the boot image header).
            when (obj.tagNo) {
                704,
                705 -> continue
                706 -> if (osPatch != null) continue
                718 -> if (vendorPatch != null) continue
                719 -> if (bootPatch != null) continue
            }
            rebuilt.add(obj)
        }
        rebuilt.add(DERTaggedObject(true, 705, ASN1Integer(AndroidUtils.osVersion.toLong())))
        if (osPatch != null && osPatch != AndroidUtils.DO_NOT_REPORT)
            rebuilt.add(DERTaggedObject(true, 706, ASN1Integer(osPatch.toLong())))
        if (vendorPatch != null && vendorPatch != AndroidUtils.DO_NOT_REPORT)
            rebuilt.add(DERTaggedObject(true, 718, ASN1Integer(vendorPatch.toLong())))
        if (bootPatch != null && bootPatch != AndroidUtils.DO_NOT_REPORT)
            rebuilt.add(DERTaggedObject(true, 719, ASN1Integer(bootPatch.toLong())))
        rebuilt.add(DERTaggedObject(704, hackedRootOfTrust))
        rebuilt.sortBy { it.tagNo }

        val rebuiltVector = ASN1EncodableVector()
        rebuilt.forEach { rebuiltVector.add(it) }

        val hackEnforced = DERSequence(rebuiltVector)
        originalEncodables[teeIndex] = hackEnforced
        val hackedSequence = DERSequence(originalEncodables)
        val hackedSequenceOctets = DEROctetString(hackedSequence)

        return Extension(ATTESTATION_OID, false, hackedSequenceOctets)
    }

    private fun containsRootOfTrust(list: ASN1Encodable): Boolean {
        if (list !is ASN1Sequence) return false
        return list.toArray().any { it is ASN1TaggedObject && it.tagNo == 704 }
    }
}
