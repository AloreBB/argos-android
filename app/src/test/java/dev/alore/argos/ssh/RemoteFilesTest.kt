package dev.alore.argos.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFilesTest {
    @Test
    fun `sanitize keeps safe chars only`() = assertEquals("mi_foto__1_.png", RemoteFiles.sanitize("mi foto (1).png"))

    @Test
    fun `sanitize drops directories`() = assertEquals("passwd", RemoteFiles.sanitize("../../etc/passwd"))

    @Test
    fun `sanitize never returns empty`() = assertEquals("archivo", RemoteFiles.sanitize(""))

    @Test
    fun `paths live in tmp`() {
        assertEquals("/tmp/argos-pasted-42-cafe0001.png", RemoteFiles.pastedImagePath("png", 42, "cafe0001"))
        assertEquals("/tmp/argos-42-cafe0001-a_b.txt", RemoteFiles.uploadedFilePath("a b.txt", 42, "cafe0001"))
    }

    @Test
    fun `default token makes names unpredictable`() {
        assertTrue(RemoteFiles.randomToken().matches(Regex("[0-9a-f]{8}")))
        assertNotEquals(RemoteFiles.uploadedFilePath("x", 1), RemoteFiles.uploadedFilePath("x", 1))
    }
}
