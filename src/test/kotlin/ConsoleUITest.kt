package net.firzen.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies non-interactive decisions used by terminal user management.
 */
class ConsoleUITest {
    /**
     * Verifies that only explicit affirmative responses confirm user deletion.
     */
    @Test
    fun `deletion confirmation accepts only yes responses`() {
        assertTrue(isDeletionConfirmed("y"))
        assertTrue(isDeletionConfirmed("YES"))
        assertFalse(isDeletionConfirmed("n"))
        assertFalse(isDeletionConfirmed(""))
        assertFalse(isDeletionConfirmed(null))
    }

    /**
     * Verifies that usage presents every supported command on its own line.
     */
    @Test
    fun `usage lists all commands in structured form`() {
        val usage = usageText("renamed-application.jar")

        assertTrue(usage.startsWith("Usage:\n"))
        assertTrue(usage.contains("java -jar renamed-application.jar <command> [arguments]"))
        assertTrue(usage.contains("\nCommands:\n"))
        assertTrue(usage.contains("\n  server "))
        assertTrue(usage.contains("\n  adduser [username] "))
        assertTrue(usage.contains("\n  passwd <username> "))
        assertTrue(usage.contains("\n  listusers "))
        assertTrue(usage.contains("\n  deluser <username> "))
        assertTrue(usage.contains("\n  --help, -h, help "))
    }
}
