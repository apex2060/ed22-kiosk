package org.ed22.kiosk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parent PIN prompt: never crashes, only a verified PIN opens the menu, and wrong guesses back off. */
class PinPromptTest {
    private val salt = ByteArray(16) { it.toByte() }
    private val stored = PinStore.hashFor("4821", salt)

    @Test
    fun emptyOrMalformedEntryNeverThrowsAndNeverMatches() {
        // Before the fix an empty entry crashed in PBKDF2 ("password empty") and the restart dropped lock task.
        for (typed in listOf("", " ", "12", "abcd", "123456789", "48 21", "４８２１")) {
            assertFalse("'$typed'", PinStore.matches(typed, salt, stored))
        }
        assertFalse(PinStore.matches("4821", ByteArray(0), stored))
        assertFalse(PinStore.matches("4821", salt, ByteArray(0)))
    }

    @Test
    fun correctPinMatchesOthersDoNot() {
        assertTrue(PinStore.matches("4821", salt, stored))
        assertFalse(PinStore.matches("4822", salt, stored))
        assertFalse(PinStore.matches("48210", salt, stored))
        assertArrayEquals(stored, PinStore.hashFor("4821", salt))
    }

    @Test
    fun onlyAVerifiedPinOpensTheMenu() {
        assertEquals(PinPromptOutcome.Granted, pinPromptOutcome("4821", PinStore.Check.OK))
        assertEquals(PinPromptOutcome.Empty, pinPromptOutcome("", PinStore.Check.WRONG))
        // Even a (bogus) OK for an empty entry does not open it.
        assertEquals(PinPromptOutcome.Empty, pinPromptOutcome("", PinStore.Check.OK))
        assertEquals(PinPromptOutcome.Wrong, pinPromptOutcome("1111", PinStore.Check.WRONG))
        assertEquals(PinPromptOutcome.Locked, pinPromptOutcome("1111", PinStore.Check.LOCKED))
        assertEquals(PinPromptOutcome.Locked, pinPromptOutcome("4821", PinStore.Check.LOCKED))
    }

    @Test
    fun fiveWrongLockAndLockoutsGrow() {
        val now = 1_000_000L
        var s = PinStore.Attempts(0, 0)
        repeat(PinStore.MAX_FAILURES - 1) {
            val r = PinStore.step(false, s, now)
            assertEquals(PinStore.Check.WRONG, r.check)
            s = r.state
        }
        val locked = PinStore.step(false, s, now)
        assertEquals(PinStore.Check.LOCKED, locked.check)
        assertEquals(now + 60_000L, locked.lockedUntil)
        assertEquals(PinStore.Attempts(0, 1), locked.state)
        // The next round of five locks for 2 minutes, then 4, 8, capped at 15.
        assertEquals(120_000L, PinStore.lockoutMs(1))
        assertEquals(240_000L, PinStore.lockoutMs(2))
        assertEquals(480_000L, PinStore.lockoutMs(3))
        assertEquals(15 * 60_000L, PinStore.lockoutMs(4))
        assertEquals(15 * 60_000L, PinStore.lockoutMs(40))
        // A correct PIN resets everything.
        assertEquals(PinStore.Step(PinStore.Check.OK, PinStore.Attempts(0, 0), 0), PinStore.step(true, PinStore.Attempts(3, 2), now))
    }
}
