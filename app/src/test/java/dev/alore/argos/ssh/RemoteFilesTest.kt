package dev.alore.argos.ssh

import org.junit.Assert.assertEquals
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
        assertEquals("/tmp/argos-pasted-42.png", RemoteFiles.pastedImagePath("png", 42))
        assertEquals("/tmp/argos-42-a_b.txt", RemoteFiles.uploadedFilePath("a b.txt", 42))
    }
}
