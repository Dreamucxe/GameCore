package com.gamecore.data.preferences

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the database passphrase.
 *
 * The passphrase is generated once, on first launch, from [SecureRandom] and then stored in an
 * [EncryptedSharedPreferences] file whose own key lives in the Android Keystore. That is the whole
 * design, and the ordering matters: the key protecting the passphrase never exists as bytes this
 * process can read, because Keystore operations happen behind a binder in keystore2 (and in the
 * TEE on hardware that has one). What is on disk is a passphrase encrypted with a key that is not
 * on disk.
 *
 * The alternative designs and why they are not used:
 *
 * - **A constant in the source.** Obfuscation (§24A.14) makes it harder to find, not absent, and
 *   one extracted string would open every install of GameCore. R8 renames symbols; it does not
 *   remove string constants.
 * - **Derived from `ANDROID_ID` or the serial.** Both are readable by any app with a shell, so the
 *   encryption would be theatre. It also breaks on a factory reset in the worst way: the key
 *   changes, the file does not, and the user's history is unreadable with no explanation.
 * - **A user-chosen passphrase.** Correct for a password manager, wrong here: GameCore's database
 *   is opened by a foreground service after a reboot, with no UI and nobody to type anything.
 *
 * A generated-once key has one real consequence, and it is faced rather than hidden: if the
 * Keystore entry is lost — an OS-level key invalidation, a restore onto a different device — the
 * passphrase cannot be recovered and the database cannot be read. [wasKeyLost] exists so the
 * database module can tell that case apart from a corrupt file and recreate the store instead of
 * throwing on every launch forever. Session history is regenerable by playing games; nothing here
 * is irreplaceable enough to justify a weaker key.
 */
@Singleton
class DatabaseKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cached: ByteArray? = null

    @Volatile
    private var keyLost: Boolean = false

    /**
     * True if the encrypted file existed but could not be opened.
     *
     * Only meaningful after [passphrase] has been called. It means the Keystore entry that
     * protects the passphrase file is gone, which in turn means the database is undecryptable —
     * not that anything is wrong with the database itself.
     */
    val wasKeyLost: Boolean
        get() = keyLost

    /**
     * The passphrase, generating and storing one on first call.
     *
     * Returns a copy each time. SQLCipher's `SupportOpenHelperFactory` takes `byte[]` and zeroes
     * the array it is given after keying the connection, so handing out the cached instance would
     * mean the second call to this method returns 32 zero bytes — a failure that looks like
     * database corruption and is not.
     */
    fun passphrase(): ByteArray {
        cached?.let { return it.copyOf() }
        synchronized(this) {
            cached?.let { return it.copyOf() }
            val resolved = load() ?: generateAndStore()
            cached = resolved
            return resolved.copyOf()
        }
    }

    /**
     * Discards the stored passphrase and forgets the cached one.
     *
     * Called only when the database file is being deleted because it cannot be opened. Clearing
     * the passphrase without deleting the file would strand the file permanently.
     */
    fun reset() {
        synchronized(this) {
            cached = null
            keyLost = false
            runCatching { preferences()?.edit()?.remove(KEY_PASSPHRASE)?.commit() }
            runCatching { context.deleteSharedPreferences(FILE_NAME) }
        }
    }

    private fun load(): ByteArray? {
        val prefs = preferences()
        if (prefs == null) {
            // The file exists on disk but will not open: the Keystore entry is gone. Note it and
            // let the caller decide, rather than crashing during dependency construction.
            keyLost = existsOnDisk()
            return null
        }
        val encoded = runCatching { prefs.getString(KEY_PASSPHRASE, null) }.getOrNull()
            ?: return null
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        // A short value means a truncated write, not a valid key; regenerate rather than key a
        // database with something weaker than intended.
        return bytes?.takeIf { it.size == KEY_BYTES }
    }

    private fun generateAndStore(): ByteArray {
        val generated = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(generated, Base64.NO_WRAP)
        // `commit`, not `apply`: the very next thing that happens is a database being opened with
        // this passphrase, and a process death between the two would leave an encrypted file whose
        // key was never persisted.
        preferences()?.edit()?.putString(KEY_PASSPHRASE, encoded)?.commit()
        return generated
    }

    private fun preferences() = runCatching {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private fun existsOnDisk(): Boolean = runCatching {
        java.io.File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml").exists()
    }.getOrDefault(false)

    private companion object {
        const val FILE_NAME = "gamecore-key"

        /** The only entry in that file. Its name is encrypted too, by AES256_SIV. */
        const val KEY_PASSPHRASE = "db_passphrase"

        /** Separate from the settings store's key, so revoking one does not lose the other. */
        const val MASTER_KEY_ALIAS = "gamecore_db_master_key"

        /** 256 bits. SQLCipher accepts a raw key of any length; this is the cipher's own width. */
        const val KEY_BYTES = 32
    }
}
