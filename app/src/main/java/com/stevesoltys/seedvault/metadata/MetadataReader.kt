/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

interface MetadataReader {

    @Throws(
        SecurityException::class,
        DecryptionFailedException::class,
        UnsupportedVersionException::class,
        IOException::class
    )
    fun readMetadata(inputStream: InputStream, expectedToken: Long): BackupMetadata

    @Throws(SecurityException::class)
    fun decode(
        bytes: ByteArray,
        expectedVersion: Byte? = null,
        expectedToken: Long? = null,
    ): BackupMetadata

}

internal class MetadataReaderImpl(private val crypto: Crypto) : MetadataReader {

    @Throws(
        SecurityException::class,
        DecryptionFailedException::class,
        UnsupportedVersionException::class,
        IOException::class
    )
    override fun readMetadata(inputStream: InputStream, expectedToken: Long): BackupMetadata {
        val version = inputStream.read().toByte()
        if (version < 0) throw IOException()
        if (version > VERSION) throw UnsupportedVersionException(version)
        if (version == 0.toByte()) return readMetadataV0(inputStream, expectedToken)

        val metadataBytes = try {
            crypto.newDecryptingStream(inputStream, getAD(version, expectedToken)).readBytes()
        } catch (e: GeneralSecurityException) {
            throw DecryptionFailedException(e)
        }
        return decode(metadataBytes, version, expectedToken)
    }

    @Throws(
        SecurityException::class,
        DecryptionFailedException::class,
        UnsupportedVersionException::class,
        IOException::class
    )
    @Suppress("Deprecation")
    private fun readMetadataV0(inputStream: InputStream, expectedToken: Long): BackupMetadata {
        val metadataBytes = try {
            crypto.decryptMultipleSegments(inputStream)
        } catch (e: AEADBadTagException) {
            throw DecryptionFailedException(e)
        }
        return decode(metadataBytes, 0.toByte(), expectedToken)
    }

    @Throws(SecurityException::class)
    override fun decode(
        bytes: ByteArray,
        expectedVersion: Byte?,
        expectedToken: Long?,
    ): BackupMetadata {
        // NOTE: We don't do extensive validation of the parsed input here,
        // because it was encrypted with authentication, so we should be able to trust it.
        //
        // However, it is important to ensure that the expected unauthenticated version and token
        // matches the authenticated version and token in the JSON.
        try {
            val json = JSONObject(bytes.toString(Utf8))
            // get backup metadata and check expectations
            val meta = json.getJSONObject(JSON_METADATA)
            val version = meta.getInt(JSON_METADATA_VERSION).toByte()
            if (expectedVersion != null && version != expectedVersion) {
                throw SecurityException(
                    "Invalid version '${version.toInt()}' in metadata," +
                        "expected '${expectedVersion.toInt()}'."
                )
            }
            val token = meta.getLong(JSON_METADATA_TOKEN)
            if (expectedToken != null && token != expectedToken) throw SecurityException(
                "Invalid token '$token' in metadata, expected '$expectedToken'."
            )
            // get package metadata
            val packageMetadataMap = PackageMetadataMap()
            for (packageName in json.keys()) {
                if (packageName == JSON_METADATA) continue
                val p = json.getJSONObject(packageName)
                val pState = when (p.optString(JSON_PACKAGE_STATE)) {
                    "" -> APK_AND_DATA
                    QUOTA_EXCEEDED.name -> QUOTA_EXCEEDED
                    NO_DATA.name -> NO_DATA
                    NOT_ALLOWED.name -> NOT_ALLOWED
                    WAS_STOPPED.name -> WAS_STOPPED
                    else -> UNKNOWN_ERROR
                }
                val pBackupType = when (p.optString(JSON_PACKAGE_BACKUP_TYPE)) {
                    BackupType.KV.name -> BackupType.KV
                    BackupType.FULL.name -> BackupType.FULL
                    // we can't fail when format version is 0,
                    // because when only backing up the APK for example, there's no type
                    else -> null
                }
                val pSize = p.optLong(JSON_PACKAGE_SIZE, -1L)
                val pName = p.optString(JSON_PACKAGE_APP_NAME)
                val pSystem = p.optBoolean(JSON_PACKAGE_SYSTEM, false)
                val pVersion = p.optLong(JSON_PACKAGE_VERSION, 0L)
                val pInstaller = p.optString(JSON_PACKAGE_INSTALLER)
                val pSha256 = p.optString(JSON_PACKAGE_SHA256)
                val pSignatures = p.optJSONArray(JSON_PACKAGE_SIGNATURES)
                val signatures = if (pSignatures == null) null else {
                    ArrayList<String>(pSignatures.length()).apply {
                        for (i in (0 until pSignatures.length())) {
                            add(pSignatures.getString(i))
                        }
                    }
                }
                packageMetadataMap[packageName] = PackageMetadata(
                    time = p.getLong(JSON_PACKAGE_TIME),
                    state = pState,
                    backupType = pBackupType,
                    size = if (pSize < 0L) null else pSize,
                    name = if (pName == "") null else pName,
                    system = pSystem,
                    isLaunchableSystemApp = p.optBoolean(JSON_PACKAGE_SYSTEM_LAUNCHER, false),
                    version = if (pVersion == 0L) null else pVersion,
                    installer = if (pInstaller == "") null else pInstaller,
                    splits = getSplits(p),
                    sha256 = if (pSha256 == "") null else pSha256,
                    signatures = signatures
                )
            }
            return BackupMetadata(
                version = version,
                token = token,
                salt = if (version == 0.toByte()) "" else meta.getString(JSON_METADATA_SALT),
                time = meta.getLong(JSON_METADATA_TIME),
                androidVersion = meta.getInt(JSON_METADATA_SDK_INT),
                androidIncremental = meta.getString(JSON_METADATA_INCREMENTAL),
                deviceName = meta.getString(JSON_METADATA_NAME),
                d2dBackup = meta.optBoolean(JSON_METADATA_D2D_BACKUP, false),
                packageMetadataMap = packageMetadataMap,
            )
        } catch (e: JSONException) {
            throw SecurityException(e)
        }
    }

    private fun getSplits(p: JSONObject): List<ApkSplit>? {
        val jsonSplits = p.optJSONArray(JSON_PACKAGE_SPLITS) ?: return null
        val splits = ArrayList<ApkSplit>(jsonSplits.length())
        for (i in 0 until jsonSplits.length()) {
            val jsonApkSplit = jsonSplits.getJSONObject(i)
            val apkSplit = ApkSplit(
                name = jsonApkSplit.getString(JSON_PACKAGE_SPLIT_NAME),
                size = jsonApkSplit.optLong(JSON_PACKAGE_SIZE, -1L).let {
                    if (it < 0L) null else it
                },
                sha256 = jsonApkSplit.getString(JSON_PACKAGE_SHA256)
            )
            splits.add(apkSplit)
        }
        return splits
    }

}
