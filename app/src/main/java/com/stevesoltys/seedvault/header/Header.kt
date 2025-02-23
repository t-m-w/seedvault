/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.header

import com.stevesoltys.seedvault.crypto.GCM_AUTHENTICATION_TAG_LENGTH
import com.stevesoltys.seedvault.crypto.TYPE_BACKUP_FULL
import com.stevesoltys.seedvault.crypto.TYPE_BACKUP_KV
import java.nio.ByteBuffer

internal const val VERSION: Byte = 1
internal const val MAX_PACKAGE_LENGTH_SIZE = 255
internal const val MAX_KEY_LENGTH_SIZE = MAX_PACKAGE_LENGTH_SIZE
internal const val MAX_VERSION_HEADER_SIZE =
    1 + Short.SIZE_BYTES * 2 + MAX_PACKAGE_LENGTH_SIZE + MAX_KEY_LENGTH_SIZE

/**
 * After the first version byte of each backup stream
 * must follow followed this header encrypted with authentication.
 */
@Deprecated("version header is in associated data now")
internal data class VersionHeader(
    internal val version: Byte = VERSION, //  1 byte
    internal val packageName: String, // ?? bytes (max 255)
    internal val key: String? = null, // ?? bytes
) {
    init {
        check(packageName.length <= MAX_PACKAGE_LENGTH_SIZE) {
            "Package $packageName has name longer than $MAX_PACKAGE_LENGTH_SIZE"
        }
        key?.let {
            check(key.length <= MAX_KEY_LENGTH_SIZE) {
                "Key $key is longer than $MAX_KEY_LENGTH_SIZE"
            }
        }
    }
}

internal fun getADForKV(version: Byte, packageName: String): ByteArray {
    val packageNameBytes = packageName.toByteArray()
    return ByteBuffer.allocate(2 + packageNameBytes.size)
        .put(version)
        .put(TYPE_BACKUP_KV)
        .put(packageNameBytes)
        .array()
}

internal fun getADForFull(version: Byte, packageName: String): ByteArray {
    val packageNameBytes = packageName.toByteArray()
    return ByteBuffer.allocate(2 + packageNameBytes.size)
        .put(version)
        .put(TYPE_BACKUP_FULL)
        .put(packageNameBytes)
        .array()
}

internal const val SEGMENT_LENGTH_SIZE: Int = Short.SIZE_BYTES
internal const val MAX_SEGMENT_LENGTH: Int = Short.MAX_VALUE.toInt()
internal const val MAX_SEGMENT_CLEARTEXT_LENGTH: Int =
    MAX_SEGMENT_LENGTH - GCM_AUTHENTICATION_TAG_LENGTH / 8
internal const val IV_SIZE: Int = 12
internal const val SEGMENT_HEADER_SIZE = SEGMENT_LENGTH_SIZE + IV_SIZE

/**
 * Each data segment must start with this header
 */
@Deprecated("Don't do manual segments, use Crypto interface instead.")
class SegmentHeader(
    internal val segmentLength: Short, //  2 bytes
    internal val nonce: ByteArray, // 12 bytes
) {
    init {
        check(nonce.size == IV_SIZE) {
            "Nonce size of ${nonce.size} is not the expected IV size of $IV_SIZE"
        }
    }
}
