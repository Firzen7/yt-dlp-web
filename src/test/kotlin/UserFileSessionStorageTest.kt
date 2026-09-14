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

        storage.write("first_id", serializedSession("alice", "192.0.2.10"))
        storage.write("second_id", serializedSession("alice", "2001:db8::10"))
        storage.write("third_id", serializedSession("bob", "198.51.100.20"))

        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()
        assertEquals(UserSession("alice", "192.0.2.10"), readSession(storage, "first_id"))
        assertTrue(sessionFile.readLines().contains("first_id\t1000\t192.0.2.10"))
        assertTrue(sessionFile.readLines().contains("second_id\t1000\t2001:db8::10"))
        assertTrue(tempDirectory.resolve("bob.sessions").toFile().exists())

        assertEquals(listOf("alice", "alice", "bob"), storage.activeSessions().map { it.username })
        assertEquals(listOf("bob"), storage.activeSessions("bob").map { it.username })
    }

    /**
     * Verifies that restored sessions use the currently configured lifetime.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `restored sessions use current lifetime`(@TempDir tempDirectory: Path) = runBlocking {
        val firstStorage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }
        firstStorage.write("session_id", serializedSession("alice", "192.0.2.10"))

        val restoredStorage = UserFileSessionStorage(tempDirectory.toFile(), 200) { 1_050 }
        val restoredSession = restoredStorage.activeSessions().single()

        assertEquals(UserSession("alice", "192.0.2.10"), readSession(restoredStorage, "session_id"))
        assertEquals(1_000, restoredSession.createdAt)
        assertEquals(1_200, restoredSession.expiresAt)
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

        storage.write("session_id", serializedSession("alice", "192.0.2.10"))
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

        storage.write("session_id", serializedSession("alice", "192.0.2.10"))
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

        storage.write("session_id", serializedSession("alice", "192.0.2.10"))
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

        storage.write("old_id", serializedSession("alice", "192.0.2.10"))
        assertTrue(sessionFile.delete())
        storage.write("new_id", serializedSession("alice", "198.51.100.20"))

        assertMissingSession(storage, "old_id")
        assertEquals(UserSession("alice", "198.51.100.20"), readSession(storage, "new_id"))
        assertFalse(sessionFile.readText().contains("old_id"))
    }

    /**
     * Verifies that old two-column records retain their timestamp during normalization.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `legacy record receives unknown client address`(@TempDir tempDirectory: Path) = runBlocking {
        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()
        sessionFile.writeText("session_id\t1100\n")
        assertTrue(sessionFile.setLastModified(1_000_000))

        val storage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }

        assertEquals(UserSession("alice", "unknown"), readSession(storage, "session_id"))
        assertEquals(
            "session_id\t1000\tunknown\n",
            sessionFile.readText()
        )
    }

    /**
     * Verifies that files written with the temporary format marker become headerless.
     *
     * @param tempDirectory temporary session directory supplied by JUnit
     */
    @Test
    fun `temporary format header is removed`(@TempDir tempDirectory: Path) = runBlocking {
        val sessionFile = tempDirectory.resolve("alice.sessions").toFile()
        sessionFile.writeText(
            "# timestamp=created-at\nsession_id\t1000\t192.0.2.10\n"
        )

        val storage = UserFileSessionStorage(tempDirectory.toFile(), 100) { 1_000 }

        assertEquals(UserSession("alice", "192.0.2.10"), readSession(storage, "session_id"))
        assertEquals("session_id\t1000\t192.0.2.10\n", sessionFile.readText())
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

    /**
     * Serializes a session in the same form Ktor supplies to the storage backend.
     *
     * @param username authenticated account name
     * @param clientAddress login-origin network address
     * @return serialized server-side session
     */
    private fun serializedSession(username: String, clientAddress: String): String {
        return UserSessionSerializer.serialize(UserSession(username, clientAddress))
    }

    /**
     * Reads and deserializes a session from its opaque identifier.
     *
     * @param storage storage containing the session
     * @param id opaque session identifier
     * @return restored user session
     */
    private suspend fun readSession(storage: UserFileSessionStorage, id: String): UserSession {
        return UserSessionSerializer.deserialize(storage.read(id))
    }
}
