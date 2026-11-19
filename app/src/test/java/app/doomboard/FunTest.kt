package app.doomboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunTest {
    @Test fun greetingByTimeOfDay() {
        assertEquals("It's 2 AM, Sam. Go to sleep 😭", Fun.greeting(2, "Sam"))
        assertEquals("It's 12 AM. Go to sleep 😭", Fun.greeting(0, null))
        assertTrue(Fun.greeting(9, null).startsWith("Good morning"))
        assertTrue(Fun.greeting(23, "Sam").startsWith("Late-night scroll, Sam"))
    }

    @Test fun roastPrefersLimitOverCount() {
        assertEquals("20 over your limit. We need to talk 🛑", Fun.roast(120, 100))
        assertTrue(Fun.roast(100, 100).startsWith("That's your limit"))
        assertTrue(Fun.roast(120, 0).startsWith("Bestie")) // no limit set: count-based roast
        assertTrue(Fun.roast(0, 50).startsWith("Clean slate"))
    }
}
