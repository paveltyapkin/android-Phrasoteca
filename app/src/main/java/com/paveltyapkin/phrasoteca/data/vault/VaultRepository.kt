package com.paveltyapkin.phrasoteca.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import com.paveltyapkin.phrasoteca.data.local.CollectionEntity
import com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase
import com.paveltyapkin.phrasoteca.data.local.VaultCardEntity
import com.paveltyapkin.phrasoteca.data.local.VaultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.88 crypto backend; not wired to the UI yet.
 *
 * Each vault receives a random 256-bit data key, wrapped twice:
 *   AES-256-GCM(PIN-derived key, data key), then
 *   AES-256-GCM(Android Keystore key, inner ciphertext).
 * This means a copied database alone cannot be brute-forced without the device Keystore key.
 * On-device attackers with app execution privileges can potentially bypass application-managed
 * PIN retries are limited by the current access UI, not tamper-proof hardware enforcement.
 */
class VaultRepository(
    context: Context,
    private val db: PhrasotecaDatabase = PhrasotecaDatabase.getInstance(context)
) {
    companion object {
        private const val PIN_LENGTH = 6
        private const val KEY_BYTES = 32
        private const val SALT_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val KDF_ITERATIONS = 210_000
        private const val ALIAS_PREFIX = "phrasoteca_vault_v1_"
        private val rng = SecureRandom()
    }

    sealed class UnlockResult {
        class Unlocked(val session: VaultSession) : UnlockResult()
        data object IncorrectPin : UnlockResult()
        data object NotFound : UnlockResult()
    }

    data class VaultCard(
        val id: Long,
        val title: String,
        val username: String,
        val password: String,
        val website: String,
        val notes: String
    )

    private val guard = Mutex()

    /** Must be called off the UI thread through the suspend API. */
    suspend fun createVault(name: String, pin: String): Long = guard.withLock {
        require(name.trim().isNotEmpty() && name.length <= 200)
        requireValidPin(pin)
        val dataKey = randomBytes(KEY_BYTES)
        val salt = randomBytes(SALT_BYTES)
        try {
            val pinKey = derivePinKey(pin, salt)
            val (pinNonce, inner) = encrypt(pinKey, dataKey, "vault-key-v1".toByteArray())
            return@withLock db.withTransaction {
                val id = db.collectionDao().insert(CollectionEntity(
                    name = name.trim(), sortOrder = db.collectionDao().nextSortOrder(),
                    defaultCardType = "VAULT", sideMode = "TWO_SIDED",
                    frontLabel = "Account", backLabel = "Secret"
                ))
                try {
                    val (keystoreNonce, outer) = encrypt(getOrCreateKeystoreKey(id), inner, aad(id))
                    db.vaultDao().insertVault(VaultEntity(
                        collectionId = id, pinSalt = salt, pinNonce = pinNonce,
                        keystoreNonce = keystoreNonce, wrappedDataKey = outer
                    ))
                    id
                } catch (e: Exception) {
                    removeKeystoreKey(id)
                    throw e
                }
            }
        } finally {
            dataKey.fill(0)
        }
    }

    /** Incorrect PIN never destroys a vault; the Activity limits retries per opening. */
    suspend fun unlock(vaultId: Long, pin: String): UnlockResult = guard.withLock {
        requireValidPin(pin)
        val meta = db.vaultDao().getVault(vaultId) ?: return@withLock UnlockResult.NotFound
        // A missing or invalid Keystore key is NOT treated as a wrong PIN.
        val inner = decrypt(getExistingKeystoreKey(vaultId), meta.keystoreNonce,
            meta.wrappedDataKey, aad(vaultId))
        val pinKey = derivePinKey(pin, meta.pinSalt)
        val dataKey = try {
            decrypt(pinKey, meta.pinNonce, inner, "vault-key-v1".toByteArray())
        } catch (_: AEADBadTagException) {
            return@withLock UnlockResult.IncorrectPin
        }
        try {
            require(dataKey.size == KEY_BYTES) { "Invalid vault key" }
            db.vaultDao().setFailedAttempts(vaultId, 0)
            UnlockResult.Unlocked(VaultSession(vaultId, dataKey.copyOf(), db))
        } finally {
            dataKey.fill(0)
        }
    }

    /**
     * Verifies the supplied PIN and deletes the entire vault under the same repository lock.
     * An incorrect PIN in the delete flow does NOT change the failed-unlock counter.
     * Do not separate authentication and deletion into two public operations.
     */
    enum class DeleteResult { DELETED, INCORRECT_PIN, NOT_FOUND }

    suspend fun deleteVault(vaultId: Long, pin: String): DeleteResult = guard.withLock {
        requireValidPin(pin)
        val collection = db.collectionDao().getById(vaultId)
            ?: return@withLock DeleteResult.NOT_FOUND
        require(collection.defaultCardType == "VAULT")
        val meta = db.vaultDao().getVault(vaultId)
            ?: return@withLock DeleteResult.NOT_FOUND
        val inner = decrypt(getExistingKeystoreKey(vaultId), meta.keystoreNonce,
            meta.wrappedDataKey, aad(vaultId))
        val pinKey = derivePinKey(pin, meta.pinSalt)
        val dataKey = try {
            decrypt(pinKey, meta.pinNonce, inner, "vault-key-v1".toByteArray())
        } catch (_: AEADBadTagException) {
            return@withLock DeleteResult.INCORRECT_PIN
        } finally {
            inner.fill(0)
        }
        try {
            check(dataKey.size == KEY_BYTES) { "Invalid vault key" }
            db.collectionDao().delete(collection)
            removeKeystoreKey(vaultId)
            DeleteResult.DELETED
        } finally {
            dataKey.fill(0)
        }
    }

    class VaultSession internal constructor(
        val vaultId: Long,
        private val secret: ByteArray,
        private val db: PhrasotecaDatabase
    ) : AutoCloseable {
        private var closed = false
        private fun key(): SecretKeySpec {
            check(!closed) { "Vault is locked" }
            return SecretKeySpec(secret, "AES")
        }
        private fun cardAad(id: Long): ByteArray = "phrasoteca:vault:$vaultId:card:$id".toByteArray()

        suspend fun addCard(title: String, username: String, password: String,
                            website: String = "", notes: String = ""): Long {
            require(title.length <= 300 && username.length <= 2000 && password.length <= 10000)
            require(website.length <= 2000 && notes.length <= 20000)
            return db.withTransaction {
                val id = db.vaultDao().insertCard(VaultCardEntity(
                    vaultId = vaultId, nonce = byteArrayOf(), ciphertext = byteArrayOf(),
                    sortOrder = db.vaultDao().getCards(vaultId).size
                ))
                val plain = encode(title, username, password, website, notes)
                try {
                    val (nonce, ciphertext) = encrypt(key(), plain, cardAad(id))
                    db.vaultDao().updateCard(vaultId, id, nonce, ciphertext)
                } finally { plain.fill(0) }
                id
            }
        }
        suspend fun readCards(): List<VaultCard> = db.vaultDao().getCards(vaultId).map { decryptCard(it) }
        suspend fun updateCard(id: Long, title: String, username: String, password: String,
                               website: String = "", notes: String = "") {
            check(db.vaultDao().getCard(vaultId, id) != null)
            val plain = encode(title, username, password, website, notes)
            try {
                val (nonce, ciphertext) = encrypt(key(), plain, cardAad(id))
                db.vaultDao().updateCard(vaultId, id, nonce, ciphertext)
            } finally { plain.fill(0) }
        }
        suspend fun deleteCard(id: Long) {
            check(!closed)
            db.vaultDao().deleteCard(vaultId, id)
        }
        private fun decryptCard(card: VaultCardEntity): VaultCard {
            val bytes = decrypt(key(), card.nonce, card.ciphertext, cardAad(card.id))
            try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                return VaultCard(card.id, json.getString("title"), json.getString("username"),
                    json.getString("password"), json.getString("website"), json.getString("notes"))
            } finally { bytes.fill(0) }
        }
        private fun encode(title: String, username: String, password: String,
                           website: String, notes: String): ByteArray = JSONObject()
            .put("title", title).put("username", username).put("password", password)
            .put("website", website).put("notes", notes).toString().toByteArray(Charsets.UTF_8)
        override fun close() {
            secret.fill(0)
            closed = true
        }
    }

    private fun requireValidPin(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }) {
            "PIN must contain exactly six digits"
        }
    }
    private suspend fun derivePinKey(pin: String, salt: ByteArray): SecretKeySpec =
        withContext(Dispatchers.Default) {
            val chars = pin.toCharArray()
            val spec = PBEKeySpec(chars, salt, KDF_ITERATIONS, KEY_BYTES * 8)
            try {
                SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded, "AES")
            } finally {
                chars.fill('\u0000')
                spec.clearPassword()
            }
        }
    private fun getOrCreateKeystoreKey(vaultId: Long): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = ALIAS_PREFIX + vaultId
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }
    private fun getExistingKeystoreKey(vaultId: Long): SecretKey =
        (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(ALIAS_PREFIX + vaultId, null) as? SecretKey)
            ?: throw IllegalStateException("Vault device key is unavailable")
    private fun removeKeystoreKey(vaultId: Long) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry(ALIAS_PREFIX + vaultId)
    }
    private fun aad(id: Long) = "phrasoteca:vault:$id:v1".toByteArray()
}

private fun randomBytes(length: Int) = ByteArray(length).also { SecureRandom().nextBytes(it) }
private fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    // Android Keystore forbids caller-provided IVs for AES-GCM encryption.
    // The provider generates a fresh random IV; store it alongside ciphertext.
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.updateAAD(aad)
    val ciphertext = cipher.doFinal(plaintext)
    return cipher.iv to ciphertext
}
private fun decrypt(key: SecretKey, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
    require(nonce.size == 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    return cipher.doFinal(ciphertext)
}
