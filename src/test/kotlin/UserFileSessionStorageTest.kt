package net.firzen.web.network

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Verifies persistent server-side session storage and expiration behavior.
 */
class UserFileSessionStorageTest {
    /**
     * Verifies that multiple sessions are stored together in their user's plaintext file.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `sessions are stored in one file per user`(@TempDir tempDirectory: Path) = runBlocking {
        val storage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }

        storage.write("first_id", "alice")
        storage.write("second_id", "alice")
        storage.write("third_id", "bob")

        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()
        assertEquals("alice", storage.read("first_id"))
        assertTrue(sessionFile.readLines().contains("first_id\t1100"))
        assertTrue(sessionFile.readLines().contains("second_id\t1100"))
        assertTrue(tempDirectory.resolve("bob.sessions").toFile().exists())
    }

    /**
     * Verifies that stored sessions remain available after storage is reconstructed.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `sessions survive storage restart`(@TempDir tempDirectory: Path) = runBlocking {
        val firstStorage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }
        firstStorage.write("session_id", "alice")

        val restoredStorage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_050 }

        assertEquals("alice", restoredStorage.read("session_id"))
    }

    /**
     * Verifies that invalidating the final session removes its user's file.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `invalidating final session removes user file`(@TempDir tempDirectory: Path) = runBlocking {
        val storage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }
        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()

        storage.write("session_id", "alice")
        storage.invalidate("session_id")

        assertFalse(sessionFile.exists())
        assertMissingSession(storage, "session_id")
    }

    /**
     * Verifies that expired sessions are rejected and removed from disk.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `expired session is rejected and removed`(@TempDir tempDirectory: Path) = runBlocking {
        var currentTime = 1_000L
        val storage = UserFileSessionStorage(tempDirectory.toFile(), 10) { currentTime }
        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()

        storage.write("session_id", "alice")
        currentTime = 1_011L

        assertMissingSession(storage, "session_id")
        assertFalse(sessionFile.exists())
    }

    /**
     * Verifies that removing a user file invalidates sessions already loaded in memory.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `external file removal invalidates cached session`(@TempDir tempDirectory: Path) = runBlocking {
        val storage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }
        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()

        storage.write("session_id", "alice")
        assertTrue(sessionFile.delete())

        assertMissingSession(storage, "session_id")
    }

    /**
     * Verifies that creating a new session cannot restore externally removed identifiers.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `new session does not restore externally removed sessions`(
        @TempDir tempDirectory: Path
    ) = runBlocking {
        val storage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }
        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()

        storage.write("old_id", "alice")
        assertTrue(sessionFile.delete())
        storage.write("new_id", "alice")

        assertMissingSession(storage, "old_id")
        assertEquals("alice", storage.read("new_id"))
        assertFalse(sessionFile.readText().contains("old_id"))
    }

    /**
     * Asserts that a session identifier is unavailable from storage.
     *
     * @param storage storage expected to reject the identifier
     * @param id unavailable session identifier
     */
    private fun assertMissingSession(storage: UserFileSessionStorage, id: String) {
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { storage.read(id) }
        }
    }
}
