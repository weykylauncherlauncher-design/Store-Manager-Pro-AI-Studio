package com.storemanager.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Argon2idPasswordHasherTest {
    @Test
    fun createsValidFormattedCredentialsAndVerifiesCorrectPassword() = runBlocking {
        val hasher = Argon2idPasswordHasher()
        val password = TEST_PASSWORD.toCharArray()
        try {
            val credentials = hasher.createCredentials(password)
            val hashFields = credentials.passwordHash.split('$')

            assertEquals(5, hashFields.size)
            assertEquals("v1", hashFields[0])
            assertEquals("argon2id", hashFields[1])
            assertEquals("v=19", hashFields[2])
            assertEquals("m=19456,t=2,p=1", hashFields[3])
            assertEquals(32, Base64.getDecoder().decode(hashFields[4]).size)
            assertEquals(16, Base64.getDecoder().decode(credentials.salt).size)
            assertTrue(hasher.verifyPassword(password, credentials.passwordHash, credentials.salt))
            assertFalse(credentials.toString().contains(credentials.passwordHash))
            assertFalse(credentials.toString().contains(credentials.salt))
            assertFalse(credentials.toString().contains(TEST_PASSWORD))
        } finally {
            password.fill('\u0000')
        }
        Unit
    }

    @Test
    fun rejectsIncorrectPassword() = runBlocking {
        val hasher = Argon2idPasswordHasher()
        val credentials = hasher.createCredentials(TEST_PASSWORD.toCharArray())
        val incorrectPassword = "another-temporary-test-password".toCharArray()
        try {
            assertFalse(hasher.verifyPassword(incorrectPassword, credentials.passwordHash, credentials.salt))
        } finally {
            incorrectPassword.fill('\u0000')
        }
    }

    @Test
    fun createsDifferentSaltAndHashForSamePassword() = runBlocking {
        val hasher = Argon2idPasswordHasher()
        val firstPassword = TEST_PASSWORD.toCharArray()
        val secondPassword = TEST_PASSWORD.toCharArray()
        try {
            val first = hasher.createCredentials(firstPassword)
            val second = hasher.createCredentials(secondPassword)

            assertFalse(first.salt == second.salt)
            assertFalse(first.passwordHash == second.passwordHash)
        } finally {
            firstPassword.fill('\u0000')
            secondPassword.fill('\u0000')
        }
    }

    @Test
    fun rejectsInvalidSaltAndCorruptedHash() = runBlocking {
        val hasher = Argon2idPasswordHasher()
        val password = TEST_PASSWORD.toCharArray()
        try {
            val credentials = hasher.createCredentials(password)

            assertThrows(PasswordCredentialFormatException::class.java) {
                runBlocking { hasher.verifyPassword(password, credentials.passwordHash, "not-base64") }
            }
            assertThrows(PasswordCredentialFormatException::class.java) {
                runBlocking {
                    hasher.verifyPassword(
                        password,
                        "v1\$argon2id\$v=19\$m=19456,t=2,p=1\$***",
                        credentials.salt
                    )
                }
            }
            assertThrows(PasswordCredentialFormatException::class.java) {
                runBlocking {
                    hasher.verifyPassword(
                        password,
                        credentials.passwordHash,
                        Base64.getEncoder().encodeToString(ByteArray(15))
                    )
                }
            }
        } finally {
            password.fill('\u0000')
        }
        Unit
    }

    @Test
    fun rejectsUnsupportedVersionAlgorithmAndCostParameters() = runBlocking {
        val hasher = Argon2idPasswordHasher()
        val password = TEST_PASSWORD.toCharArray()
        try {
            val credentials = hasher.createCredentials(password)

            listOf(
                credentials.passwordHash.replaceFirst("v1\$", "v2\$"),
                credentials.passwordHash.replaceFirst("argon2id", "argon2i"),
                credentials.passwordHash.replaceFirst("v=19", "v=16"),
                credentials.passwordHash.replaceFirst("m=19456", "m=999999999"),
                credentials.passwordHash.replaceFirst("t=2", "t=0"),
                credentials.passwordHash.replaceFirst("p=1", "p=2")
            ).forEach { unsupportedHash ->
                assertThrows(PasswordCredentialFormatException::class.java) {
                    runBlocking { hasher.verifyPassword(password, unsupportedHash, credentials.salt) }
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    @Test
    fun rejectsEmptyAndOversizedPasswordsBeforeHashing() = runBlocking {
        val hasher = Argon2idPasswordHasher()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { hasher.createCredentials(charArrayOf()) }
        }
        val oversizedPassword = CharArray(1_025) { 'x' }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { hasher.createCredentials(oversizedPassword) }
            }
        } finally {
            oversizedPassword.fill('\u0000')
        }
        Unit
    }

    @Test
    fun processWideSemaphoreSerializesDifferentHasherInstancesAndReleasesAfterSuccess() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CountDownLatch(1)
        val activeDerivations = AtomicInteger()
        val maximumActiveDerivations = AtomicInteger()

        fun trackDerivation() {
            val active = activeDerivations.incrementAndGet()
            maximumActiveDerivations.updateAndGet { maxOf(it, active) }
        }

        val firstHasher = Argon2idPasswordHasher.forTesting { _, _, output ->
            trackDerivation()
            try {
                firstEntered.complete(Unit)
                releaseFirst.await()
                output.fill(1)
            } finally {
                activeDerivations.decrementAndGet()
            }
        }
        val secondHasher = Argon2idPasswordHasher.forTesting { _, _, output ->
            trackDerivation()
            try {
                secondEntered.complete(Unit)
                output.fill(2)
            } finally {
                activeDerivations.decrementAndGet()
            }
        }

        val first = async { firstHasher.createCredentials("first".toCharArray()) }
        try {
            firstEntered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                secondHasher.createCredentials("second".toCharArray())
            }

            assertFalse(secondEntered.isCompleted)
            releaseFirst.countDown()
            first.await()
            second.await()

            assertTrue(secondEntered.isCompleted)
            assertEquals(1, maximumActiveDerivations.get())
        } finally {
            releaseFirst.countDown()
            first.cancel()
        }
    }

    @Test
    fun processWideSemaphoreIsReleasedAfterDerivationException() = runBlocking {
        val expectedFailure = IllegalStateException("test derivation failure")
        val failingHasher = Argon2idPasswordHasher.forTesting { _, _, _ -> throw expectedFailure }
        val succeedingHasher = Argon2idPasswordHasher.forTesting { _, _, output -> output.fill(7) }

        val actualFailure = assertThrows(IllegalStateException::class.java) {
            runBlocking { failingHasher.createCredentials("temporary".toCharArray()) }
        }
        assertEquals(expectedFailure.message, actualFailure.message)

        val credentials = succeedingHasher.createCredentials("temporary".toCharArray())
        assertTrue(credentials.passwordHash.startsWith("v1\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
    }

    @Test
    fun cancellationWhileWaitingDoesNotConsumePermitAndRunningDerivationKeepsPermitUntilComplete() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val cancelledWaiterEntered = CompletableDeferred<Unit>()
        val nextWaiterEntered = CompletableDeferred<Unit>()
        val releaseFirst = CountDownLatch(1)
        val firstHasher = Argon2idPasswordHasher.forTesting { _, _, output ->
            try {
                firstEntered.complete(Unit)
                releaseFirst.await()
                output.fill(1)
            } finally {
                output.fill(0)
            }
        }
        val cancelledWaiterHasher = Argon2idPasswordHasher.forTesting { _, _, output ->
            cancelledWaiterEntered.complete(Unit)
            output.fill(2)
        }
        val nextWaiterHasher = Argon2idPasswordHasher.forTesting { _, _, output ->
            nextWaiterEntered.complete(Unit)
            output.fill(3)
        }

        val running = async { firstHasher.createCredentials("first".toCharArray()) }
        try {
            firstEntered.await()
            running.cancel()

            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                cancelledWaiterHasher.createCredentials("cancelled".toCharArray())
            }
            cancelledWaiter.cancelAndJoin()
            assertFalse(cancelledWaiterEntered.isCompleted)

            val nextWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                nextWaiterHasher.createCredentials("next".toCharArray())
            }
            assertFalse(nextWaiterEntered.isCompleted)

            releaseFirst.countDown()
            running.join()
            nextWaiter.await()

            assertTrue(running.isCancelled)
            assertTrue(nextWaiterEntered.isCompleted)
        } finally {
            releaseFirst.countDown()
            running.cancel()
        }
    }

    @Test
    fun clearsDerivedOutputWhenDerivationThrows() = runBlocking {
        val capturedOutput = AtomicReference<ByteArray?>()
        val hasher = Argon2idPasswordHasher.forTesting { _, _, output ->
            capturedOutput.set(output)
            output.fill(0x5A)
            throw IllegalStateException("test derivation failure")
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { hasher.createCredentials("temporary".toCharArray()) }
        }

        val outputAfterFailure = capturedOutput.get()
        assertNotNull(outputAfterFailure)
        assertTrue(outputAfterFailure!!.all { it == 0.toByte() })
    }

    private companion object {
        const val TEST_PASSWORD = "temporary-password-for-unit-test"
    }
}
