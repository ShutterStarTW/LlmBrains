package com.shutterstar.agenthub.projects.ui

import kotlin.math.abs

/**
 * One list/details divider position shared by the Projects, Agents and Skills tabs, so the three
 * tabs split identically in a wide layout — and dragging the divider on one tab moves it on the
 * others instead of every tab keeping its own, slowly diverging, ratio.
 */
internal class SharedSplitProportion(initial: Float = DEFAULT) {
    var value: Float = initial
        private set

    private val listeners = mutableListOf<(Float) -> Unit>()

    fun addListener(listener: (Float) -> Unit) {
        listeners += listener
    }

    /** Records [newValue] and tells every listener; a no-op for an unchanged value, which also ends echo loops. */
    fun update(newValue: Float) {
        if (abs(newValue - value) < EPSILON) return
        value = newValue
        listeners.toList().forEach { it(newValue) }
    }

    companion object {
        const val DEFAULT = 0.42f
        private const val EPSILON = 0.0005f
    }
}
