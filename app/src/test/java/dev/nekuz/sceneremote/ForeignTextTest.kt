package dev.nekuz.sceneremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignTextTest {
    private val fallback = "com.example.app"

    @Test
    fun keepsOrdinaryLabels() {
        assertEquals("Maps", ForeignText.label("Maps", fallback))
        assertEquals("Café Noir", ForeignText.label("Café Noir", fallback))
        assertEquals("日本語アプリ", ForeignText.label("日本語アプリ", fallback))
    }

    @Test
    fun removesControlCharacters() {
        assertEquals("evilline", ForeignText.label("evil\nline", fallback))
        assertEquals("ab", ForeignText.label("a\u0000\u0007b\r", fallback))
    }

    @Test
    fun removesBidiAndFormatCharacters() {
        assertEquals("gpj.exe", ForeignText.label("‮gpj.exe‬", fallback))
        assertEquals("ab", ForeignText.label("a⁦⁩​‍­﻿b", fallback))
        assertEquals("ab", ForeignText.label("a  b", fallback))
    }

    @Test
    fun removesPrivateUseAndLoneSurrogates() {
        assertEquals("ab", ForeignText.label("a\uD800b", fallback))
    }

    @Test
    fun fallsBackWhenNothingIsVisible() {
        assertEquals(fallback, ForeignText.label(null, fallback))
        assertEquals(fallback, ForeignText.label("", fallback))
        assertEquals(fallback, ForeignText.label("    　", fallback))
        assertEquals(fallback, ForeignText.label("‮​", fallback))
        assertEquals(fallback, ForeignText.label("ㅤᅟ⠀", fallback))
        assertEquals(fallback, ForeignText.label("́́", fallback))
    }

    @Test
    fun collapsesAndTrimsSpaces() {
        assertEquals("a b", ForeignText.label("  a  　  b  ", fallback))
    }

    @Test
    fun capsLengthAndMarksIt() {
        val label = ForeignText.label("x".repeat(5000), fallback)
        assertEquals(ForeignText.MAX_LABEL + 1, label.length)
        assertTrue(label.endsWith("…"))
    }

    @Test
    fun doesNotMarkLabelAtExactCap() {
        val label = ForeignText.label("x".repeat(ForeignText.MAX_LABEL), fallback)
        assertEquals(ForeignText.MAX_LABEL, label.length)
        assertFalse(label.endsWith("…"))
    }

    @Test
    fun capNeverSplitsSurrogatePair() {
        val label = ForeignText.clean("😀".repeat(10), 7)
        assertEquals("😀😀😀…", label)
    }

    @Test
    fun limitsCombiningMarkStacks() {
        assertEquals("á́́́b", ForeignText.label("a" + "́".repeat(200) + "b", fallback))
    }

    @Test
    fun boundsVeryLongInput() {
        val hiddenThenText = "​".repeat(200_000) + "text"
        assertEquals(fallback, ForeignText.label(hiddenThenText, fallback))
    }

    @Test
    fun optionalKeepsAbsenceAndUnprintableApart() {
        assertNull(ForeignText.optional(null))
        assertNull(ForeignText.optional(""))
        assertEquals(ForeignText.UNPRINTABLE, ForeignText.optional("‮"))
        assertEquals("a.b", ForeignText.optional("a.‮b"))
    }
}
