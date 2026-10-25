package dev.alore.argos.model

import dev.alore.argos.model.SessionNameValidator.ValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNameValidatorTest {
    @Test
    fun `valid name is returned trimmed`() = assertEquals("main", SessionNameValidator.validate("  main  "))

    @Test
    fun `slash is allowed for grouping`() =
        assertEquals("magic-agents/backend", SessionNameValidator.validate("magic-agents/backend"))

    @Test
    fun `blank throws Empty`() {
        assertThrows(ValidationError.Empty::class.java) { SessionNameValidator.validate("   ") }
    }

    @Test
    fun `tmux separators are forbidden`() {
        for (c in listOf(':', '.')) {
            val e = assertThrows(ValidationError.ForbiddenCharacter::class.java) { SessionNameValidator.validate("a${c}b") }
            assertEquals(c, e.character)
        }
    }

    @Test
    fun `control characters are rejected`() {
        for (input in listOf("a\nb", "a\tb", "a\u0007b", "a​b")) {
            assertThrows(input, ValidationError.ControlCharacter::class.java) { SessionNameValidator.validate(input) }
        }
    }

    @Test
    fun `leading dash is rejected`() {
        assertThrows(ValidationError.LeadingDash::class.java) { SessionNameValidator.validate("-X") }
    }

    @Test
    fun `isValid mirrors validate`() {
        assertTrue(SessionNameValidator.isValid("ok"))
        assertFalse(SessionNameValidator.isValid(""))
        assertFalse(SessionNameValidator.isValid("a:b"))
        assertFalse(SessionNameValidator.isValid("-x"))
    }

    @Test
    fun `live message ignores empty but reports invalid`() {
        assertNull(SessionNameValidator.liveValidationMessage(""))
        assertNull(SessionNameValidator.liveValidationMessage("ok"))
        assertNotNull(SessionNameValidator.liveValidationMessage("a.b"))
    }
}
