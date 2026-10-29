package com.animes

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared Abyss byte transforms, kept separate so the crypto primitives can be unit-tested on the JVM. */
internal object AbyssCrypto {
    fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun decryptCtr(ciphertext: ByteArray, keyHex: String): ByteArray {
        require(keyHex.length == 32 && keyHex.all { it in "0123456789abcdefABCDEF" }) { "Expected a 32-character MD5 hex key" }
        val key = keyHex.toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        return cipher.doFinal(ciphertext)
    }

    fun decryptMp4Prefix(bytes: ByteArray, length: Int, filename: String) {
        require(length in 0..bytes.size)
        val clear = decryptCtr(bytes.copyOfRange(0, length), md5Hex(filename))
        System.arraycopy(clear, 0, bytes, 0, clear.size)
    }

    fun hasIsoBmffFtyp(bytes: ByteArray): Boolean =
        bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
}
