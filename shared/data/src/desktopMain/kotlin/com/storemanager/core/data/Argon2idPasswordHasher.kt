package com.storemanager.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class PasswordCredentials(
    val passwordHash: String,
    val salt: String
) {
    override fun toString(): String = "PasswordCredentials(<redacted>)"
}

class PasswordCredentialFormatException(message: String) : IllegalArgumentException(message)

internal fun interface Argon2KeyDeriver {
    fun derive(password: ByteArray, salt: ByteArray, output: ByteArray)
}

/**
 * Argon2 work runs on Dispatchers.Default and is limited to one operation per process.
 * Cancellation while derivation is running is observed after the synchronous operation
 * finishes; the permit remains held until its cleanup completes.
 */
class Argon2idPasswordHasher private constructor(
    private val keyDeriver: Argon2KeyDeriver
) {
    constructor() : this(Argon2KeyDeriver(::deriveWithBouncyCastle))

    suspend fun createCredentials(password: CharArray): PasswordCredentials {
        validatePasswordLength(password)

        return processSemaphore.withPermit {
            withContext(Dispatchers.Default) {
                val passwordBytes = encodePassword(password)
                try {
                    val saltBytes = ByteArray(SALT_LENGTH).also(secureRandom::nextBytes)
                    try {
                        val derivedKey = deriveKey(passwordBytes, saltBytes)
                        try {
                            PasswordCredentials(
                                passwordHash = "$HASH_FORMAT_PREFIX${Base64.getEncoder().encodeToString(derivedKey)}",
                                salt = Base64.getEncoder().encodeToString(saltBytes)
                            )
                        } finally {
                            derivedKey.fill(0)
                        }
                    } finally {
                        saltBytes.fill(0)
                    }
                } finally {
                    passwordBytes.fill(0)
                }
            }
        }
    }

    suspend fun verifyPassword(
        password: CharArray,
        passwordHash: String,
        salt: String
    ): Boolean {
        validatePasswordLength(password)
        val expectedKey = parseHash(passwordHash)
        var saltBytes: ByteArray? = null
        try {
            if (salt.length != SALT_BASE64_LENGTH) {
                throw PasswordCredentialFormatException("Invalid stored credential format")
            }
            val parsedSalt = decodeCanonicalBase64(salt, "Invalid stored credential format")
            saltBytes = parsedSalt

            if (parsedSalt.size != SALT_LENGTH) {
                throw PasswordCredentialFormatException("Invalid stored credential format")
            }

            return processSemaphore.withPermit {
                withContext(Dispatchers.Default) {
                    val passwordBytes = encodePassword(password)
                    try {
                        val actualKey = deriveKey(passwordBytes, parsedSalt)
                        try {
                            MessageDigest.isEqual(expectedKey, actualKey)
                        } finally {
                            actualKey.fill(0)
                        }
                    } finally {
                        passwordBytes.fill(0)
                    }
                }
            }
        } finally {
            expectedKey.fill(0)
            saltBytes?.fill(0)
        }
    }

    private fun deriveKey(password: ByteArray, salt: ByteArray): ByteArray {
        val output = ByteArray(KEY_LENGTH)
        var completed = false
        try {
            keyDeriver.derive(password, salt, output)
            completed = true
            return output
        } finally {
            if (!completed) {
                output.fill(0)
            }
        }
    }

    companion object {
        private val secureRandom = SecureRandom()
        private val processSemaphore = Semaphore(permits = 1)

        internal fun forTesting(keyDeriver: Argon2KeyDeriver) = Argon2idPasswordHasher(keyDeriver)

        private fun deriveWithBouncyCastle(password: ByteArray, salt: ByteArray, output: ByteArray) {
            val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
            val parameters = builder.build()

            try {
                Argon2BytesGenerator().run {
                    init(parameters)
                    generateBytes(password, output)
                }
            } finally {
                try {
                    parameters.clear()
                } finally {
                    builder.clear()
                }
            }
        }

        private fun validatePasswordLength(password: CharArray) {
            require(password.isNotEmpty()) { "Password must not be empty" }
            require(password.size <= MAX_PASSWORD_UTF8_BYTES) { "Password exceeds the supported length" }
        }

        private fun encodePassword(password: CharArray): ByteArray {
            val encoded = try {
                StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("Password contains invalid Unicode")
            }

            try {
                require(encoded.remaining() in 1..MAX_PASSWORD_UTF8_BYTES) {
                    "Password exceeds the supported length"
                }
                return ByteArray(encoded.remaining()).also(encoded::get)
            } finally {
                if (encoded.hasArray()) {
                    encoded.array().fill(0)
                }
            }
        }

        private fun parseHash(passwordHash: String): ByteArray {
            if (passwordHash.length > MAX_HASH_TEXT_LENGTH) {
                throw PasswordCredentialFormatException("Invalid stored credential format")
            }
            val fields = passwordHash.split('$')
            if (fields.size != HASH_FIELD_COUNT || fields[0] != STORAGE_VERSION) {
                throw PasswordCredentialFormatException("Invalid stored credential format")
            }
            if (fields[1] != HASH_ALGORITHM || fields[2] != ARGON2_VERSION) {
                throw PasswordCredentialFormatException("Unsupported password hash format")
            }
            if (fields[3] != ARGON2_PARAMETERS) {
                throw PasswordCredentialFormatException("Unsupported password hash parameters")
            }

            val digest = decodeCanonicalBase64(fields[4], "Invalid stored credential format")
            if (digest.size != KEY_LENGTH) {
                digest.fill(0)
                throw PasswordCredentialFormatException("Invalid stored credential format")
            }
            return digest
        }

        private fun decodeCanonicalBase64(value: String, errorMessage: String): ByteArray {
            val decoded = try {
                Base64.getDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                throw PasswordCredentialFormatException(errorMessage)
            }

            if (Base64.getEncoder().encodeToString(decoded) != value) {
                decoded.fill(0)
                throw PasswordCredentialFormatException(errorMessage)
            }
            return decoded
        }

        private const val STORAGE_VERSION = "v1"
        private const val HASH_ALGORITHM = "argon2id"
        private const val ARGON2_VERSION = "v=19"
        private const val ARGON2_PARAMETERS = "m=19456,t=2,p=1"
        private const val HASH_FORMAT_PREFIX =
            "${STORAGE_VERSION}\$${HASH_ALGORITHM}\$${ARGON2_VERSION}\$${ARGON2_PARAMETERS}\$"
        private const val HASH_FIELD_COUNT = 5
        private const val MAX_HASH_TEXT_LENGTH = 128
        private const val MEMORY_KIB = 19_456
        private const val ITERATIONS = 2
        private const val PARALLELISM = 1
        private const val SALT_LENGTH = 16
        private const val SALT_BASE64_LENGTH = 24
        private const val KEY_LENGTH = 32
        private const val MAX_PASSWORD_UTF8_BYTES = 1_024
    }
}
