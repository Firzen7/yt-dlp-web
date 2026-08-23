package net.firzen.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the command-line help content.
 */
class MainTest {
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
