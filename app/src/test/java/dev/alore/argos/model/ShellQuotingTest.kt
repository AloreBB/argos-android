package dev.alore.argos.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuotingTest {
    @Test
    fun `simple text is single quoted`() = assertEquals("'main'", ShellQuoting.singleQuoted("main"))

    @Test
    fun `empty string is still an argument`() = assertEquals("''", ShellQuoting.singleQuoted(""))

    @Test
    fun `inner quote uses the POSIX escape`() = assertEquals("'a'\\''b'", ShellQuoting.singleQuoted("a'b"))

    @Test
    fun `metacharacters stay literal`() {
        listOf("a b", "a;rm -rf /", "a&&b", "a|b", "\$(whoami)", "`id`", "a\$VAR", "a\nb", "a\\b")
            .forEach { assertEquals("'$it'", ShellQuoting.singleQuoted(it)) }
    }

    @Test
    fun `each quote is escaped independently`() {
        val esc = "'\\''"
        assertEquals("'$esc$esc'", ShellQuoting.singleQuoted("''"))
    }

    @Test
    fun `quoted value round-trips through a real shell`() {
        val nasty = "it's \$(rm -rf /) `id` ; | & \\ \"x\""
        val process = ProcessBuilder("sh", "-c", "printf %s ${ShellQuoting.singleQuoted(nasty)}").start()
        val out = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        assertEquals(nasty, out)
    }
}
