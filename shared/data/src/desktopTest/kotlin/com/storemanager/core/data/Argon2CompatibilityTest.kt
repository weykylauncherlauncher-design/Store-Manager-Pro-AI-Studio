package com.storemanager.core.data

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

class Argon2CompatibilityTest {
    @Test
    fun argon2idGeneratesAndVerifiesCredentialsWithRandomSalt() {
        val password = "temporary-test-password".toCharArray()
        val incorrectPassword = "incorrect-test-password".toCharArray()
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val secondSalt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val hash = derive(password, salt)
        val verifiedHash = derive(password, salt)
        val incorrectHash = derive(incorrectPassword, salt)
        val secondHash = derive(password, secondSalt)

        try {
            assertEquals(HASH_LENGTH, hash.size)
            assertTrue(MessageDigest.isEqual(hash, verifiedHash))
            assertFalse(MessageDigest.isEqual(hash, incorrectHash))
            assertFalse(MessageDigest.isEqual(salt, secondSalt))
            assertFalse(MessageDigest.isEqual(hash, secondHash))
        } finally {
            password.fill('\u0000')
            incorrectPassword.fill('\u0000')
            salt.fill(0)
            secondSalt.fill(0)
            hash.fill(0)
            verifiedHash.fill(0)
            incorrectHash.fill(0)
            secondHash.fill(0)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(MEMORY_KIB)
            .withIterations(ITERATIONS)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
        val parameters = builder.build()
        return try {
            Argon2BytesGenerator().run {
                init(parameters)
                ByteArray(HASH_LENGTH).also { generateBytes(password, it) }
            }
        } finally {
            parameters.clear()
            builder.clear()
        }
    }

    private companion object {
        const val SALT_LENGTH = 16
        const val HASH_LENGTH = 32
        const val MEMORY_KIB = 19_456
        const val ITERATIONS = 2
        const val PARALLELISM = 1
    }
}
