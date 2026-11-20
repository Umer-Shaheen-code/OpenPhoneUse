package com.phoneuse.overlay

import com.phoneuse.screen.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffPlacementTest {
    private val area = Bounds(40, 80, 360, 680)

    @Test
    fun bottomKeypadLeavesTheBarAtTheTop() {
        val keypad = listOf(Bounds(40, 400, 360, 680))
        assertEquals(80, handoffBarTop(area, 160, 48, keypad, preferBottom = false))
        assertEquals(352, handoffBarTop(area, 160, 48, keypad, preferBottom = true))
    }

    @Test
    fun topKeypadPlacesTheBarJustBelowItsLastRow() {
        val rows = listOf(Bounds(40, 150, 360, 230), Bounds(40, 70, 360, 160))
        assertEquals(230, handoffBarTop(area, 160, 48, rows, preferBottom = false))
    }

    @Test
    fun centralGapFitsTheBarExactlyFromEitherDirection() {
        val controls = listOf(Bounds(40, 310, 360, 680), Bounds(40, 70, 360, 262))
        assertEquals(262, handoffBarTop(area, 160, 48, controls, preferBottom = false))
        assertEquals(262, handoffBarTop(area, 160, 48, controls, preferBottom = true))
    }

    @Test
    fun controlsTouchingTheBarAtItsSidesDoNotDisplaceIt() {
        val sides = listOf(Bounds(40, 80, 120, 680), Bounds(280, 80, 360, 680))
        assertEquals(80, handoffBarTop(area, 160, 48, sides, preferBottom = false))
        assertEquals(632, handoffBarTop(area, 160, 48, sides, preferBottom = true))
    }

    @Test
    fun noGapTallEnoughFallsBackToTheRequestedEdge() {
        val controls = listOf(Bounds(40, 80, 360, 280), Bounds(40, 300, 360, 680))
        assertEquals(80, handoffBarTop(area, 160, 48, controls, preferBottom = false))
        assertEquals(632, handoffBarTop(area, 160, 48, controls, preferBottom = true))
    }

    @Test
    fun areaShorterThanTheBarAnchorsItAtTheTop() {
        val short = Bounds(40, 120, 360, 140)
        assertEquals(120, handoffBarTop(short, 160, 48, emptyList(), preferBottom = false))
        assertEquals(120, handoffBarTop(short, 160, 48, emptyList(), preferBottom = true))
    }

    @Test
    fun narrowWindowUsesItsFullWidth() {
        val narrow = Bounds(75, 80, 105, 680)
        val control = Bounds(75, 80, 77, 130)
        assertEquals(130, handoffBarTop(narrow, 160, 48, listOf(control), preferBottom = false))
    }
}
