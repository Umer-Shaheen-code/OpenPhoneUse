package com.phoneuse.overlay

import com.phoneuse.screen.Bounds

/** The closest clear position to the requested edge for a horizontally centered handoff bar. */
internal fun handoffBarTop(
    area: Bounds,
    barWidth: Int,
    barHeight: Int,
    controls: List<Bounds>,
    preferBottom: Boolean,
): Int {
    if (area.isEmpty || barHeight >= area.bottom - area.top) return area.top
    val lastTop = area.bottom - barHeight.coerceAtLeast(0)
    val edge = if (preferBottom) lastTop else area.top
    if (barWidth <= 0 || barHeight <= 0) return edge

    val width = minOf(barWidth, area.right - area.left)
    val left = area.left + (area.right - area.left - width) / 2
    val ordered = if (preferBottom) controls.sortedByDescending { it.bottom } else controls.sortedBy { it.top }
    var top = edge
    for (control in ordered) {
        if (control.intersects(Bounds(left, top, left + width, top + barHeight))) {
            // Every position up to the far side of this control overlaps it.
            top = if (preferBottom) control.top - barHeight else control.bottom
            if (top !in area.top..lastTop) return edge
        }
    }
    return top
}
