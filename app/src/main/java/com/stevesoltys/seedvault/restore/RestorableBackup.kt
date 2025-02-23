/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap

data class RestorableBackup(val backupMetadata: BackupMetadata) {

    val name: String
        get() = backupMetadata.deviceName

    val version: Byte
        get() = backupMetadata.version

    val token: Long
        get() = backupMetadata.token

    val salt: String
        get() = backupMetadata.salt

    val time: Long
        get() = backupMetadata.time

    val size: Long?
        get() = backupMetadata.size

    val deviceName: String
        get() = backupMetadata.deviceName

    val d2dBackup: Boolean
        get() = backupMetadata.d2dBackup

    val packageMetadataMap: PackageMetadataMap
        get() = backupMetadata.packageMetadataMap

}
