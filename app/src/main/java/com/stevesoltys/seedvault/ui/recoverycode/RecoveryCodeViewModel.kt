/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.recoverycode

import android.app.backup.IBackupManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.Mnemonics.ChecksumException
import cash.z.ecc.android.bip39.Mnemonics.InvalidWordException
import cash.z.ecc.android.bip39.Mnemonics.WordCountException
import cash.z.ecc.android.bip39.toSeed
import com.stevesoltys.seedvault.App
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.transport.backup.BackupInitializer
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.StorageBackup
import java.io.IOException

internal const val WORD_NUM = 12

private val TAG = RecoveryCodeViewModel::class.java.simpleName

internal class RecoveryCodeViewModel(
    app: App,
    private val crypto: Crypto,
    private val keyManager: KeyManager,
    private val backupManager: IBackupManager,
    private val backupInitializer: BackupInitializer,
    private val notificationManager: BackupNotificationManager,
    private val storageBackup: StorageBackup,
) : AndroidViewModel(app) {

    internal val wordList: List<CharArray> by lazy {
        // we use our own entropy to not having to trust the library to use SecureRandom
        val entropy = crypto.getRandomBytes(Mnemonics.WordCount.COUNT_12.bitLength / 8)
        // create the words from the entropy
        Mnemonics.MnemonicCode(entropy).words
    }

    private val mConfirmButtonClicked = MutableLiveEvent<Boolean>()
    internal val confirmButtonClicked: LiveEvent<Boolean> = mConfirmButtonClicked
    internal fun onConfirmButtonClicked() = mConfirmButtonClicked.setEvent(true)

    private val mRecoveryCodeSaved = MutableLiveEvent<Boolean>()
    internal val recoveryCodeSaved: LiveEvent<Boolean> = mRecoveryCodeSaved

    private val mExistingCodeChecked = MutableLiveEvent<Boolean>()
    internal val existingCodeChecked: LiveEvent<Boolean> = mExistingCodeChecked

    internal var isRestore: Boolean = false

    @Throws(InvalidWordException::class, ChecksumException::class)
    fun validateCode(input: List<CharSequence>): Mnemonics.MnemonicCode {
        val code = Mnemonics.MnemonicCode(input.toMnemonicChars())
        try {
            code.validate()
        } catch (e: WordCountException) {
            throw AssertionError(e)
        }
        return code
    }

    /**
     * Verifies existing recovery code and returns result via [existingCodeChecked].
     */
    fun verifyExistingCode(input: List<CharSequence>) {
        // we validate the code again, just in case
        val seed = validateCode(input).toSeed()
        val verified = crypto.verifyBackupKey(seed)
        // store main key at this opportunity if it is still missing
        if (verified && !keyManager.hasMainKey()) keyManager.storeMainKey(seed)
        mExistingCodeChecked.setEvent(verified)
        if (verified) notificationManager.onNoMainKeyErrorFixed()
    }

    /**
     * Stores a new recovery code and returns result via [recoveryCodeSaved].
     */
    fun storeNewCode(input: List<CharSequence>) {
        // we validate the code again, just in case
        val seed = validateCode(input).toSeed()
        keyManager.storeBackupKey(seed)
        keyManager.storeMainKey(seed)
        mRecoveryCodeSaved.setEvent(true)
        notificationManager.onNoMainKeyErrorFixed()
    }

    /**
     * Deletes all storage backups for current user and clears the storage backup cache.
     * Also starts a new app data restore set and initializes it.
     *
     * The reason is that old backups won't be readable anymore with the new key.
     * We can't delete other backups safely, because we can't be sure
     * that they don't belong to a different device or user.
     */
    fun reinitializeBackupLocation() {
        Log.d(TAG, "Re-initializing backup location...")
        // TODO this code is almost identical to BackupStorageViewModel#onLocationSet(), unify?
        GlobalScope.launch(Dispatchers.IO) {
            // remove old storage snapshots and clear cache
            storageBackup.init()
            try {
                // initialize the new location
                if (backupManager.isBackupEnabled) backupInitializer.initialize({ }) {
                    // no-op
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting new RestoreSet", e)
            }
        }
    }

}

internal fun List<CharSequence>.toMnemonicChars(): CharArray {
    return joinToString(" ").toCharArray()
}
