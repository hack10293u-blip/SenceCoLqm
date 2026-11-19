package vn.codex.skinlab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

internal object LocalAdbIdentity {
    private const val wrappingAlias = "sencelab-local-adb-wrap"
    private const val ivSize = 12

    @Synchronized fun load(context: Context): KeyStore.PrivateKeyEntry {
        val file = AtomicFile(File(context.noBackupFilesDir, "local-adb-key"))
        val encrypted = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            null
        }
        val key = wrappingKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val store = KeyStore.getInstance("PKCS12")
        val password = KeyStore.PasswordProtection(charArrayOf())
        if (encrypted != null) {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 0, ivSize))
            val decoded = cipher.doFinal(encrypted, ivSize, encrypted.size - ivSize)
            try {
                store.load(decoded.inputStream(), password.password)
                return store.getEntry("adb", password) as KeyStore.PrivateKeyEntry
            } finally {
                decoded.fill(0)
            }
        }

        // Conscrypt needs a software RSA key for its TLS client signature.
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val owner = X500Principal("CN=SenceLab")
        val now = System.currentTimeMillis()
        @Suppress("DEPRECATION")
        val certificate = X509V3CertificateGenerator().apply {
            setIssuerDN(owner)
            setSubjectDN(owner)
            setSerialNumber(BigInteger(128, SecureRandom()).setBit(127))
            setNotBefore(Date(now - TimeUnit.DAYS.toMillis(1)))
            setNotAfter(Date(now + TimeUnit.DAYS.toMillis(3650)))
            setPublicKey(pair.public)
            setSignatureAlgorithm("SHA256withRSA")
        }.generate(pair.private)
        val entry = KeyStore.PrivateKeyEntry(pair.private, arrayOf(certificate))
        store.load(null, password.password)
        store.setEntry("adb", entry, password)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == ivSize)
        val encoded = ByteArrayOutputStream().apply { store.store(this, password.password) }.toByteArray()
        val sealed = try {
            cipher.doFinal(encoded)
        } finally {
            encoded.fill(0)
        }
        val output = file.startWrite()
        try {
            output.write(iv)
            output.write(sealed)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        return entry
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(wrappingAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(wrappingAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
