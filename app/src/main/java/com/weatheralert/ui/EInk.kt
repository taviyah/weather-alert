package com.weatheralert.ui

import android.os.Build
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.InteractionSource

/**
 * True when the screen is very likely electronic paper.
 *
 * There is no Android API that reports "this is E Ink", so this matches the handful of makers who
 * only ship e-paper devices. It is a *default*, not a verdict — the user can flip the setting
 * either way, which is why a false negative here is cheap.
 */
object EInkDetect {

    private val EPAPER_BRANDS = setOf(
        "bigme", "onyx", "boox", "boyue", "likebook", "dasung", "pocketbook",
        "meebook", "supernote", "ratta", "remarkable", "tolino", "mudita",
        "minimal", "hanvon", "inkpalm", "moaan", "xiaomi_inkpalm",
    )

    fun looksLikeEInk(): Boolean {
        val fields = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.PRODUCT,
            Build.DEVICE,
        ).map { it.lowercase() }
        return fields.any { field -> EPAPER_BRANDS.any { field.contains(it) } }
    }
}

/**
 * Whether to render for e-paper: pure black on pure white, no shadows, no animation, and never
 * colour alone to carry meaning.
 */
val LocalEInk = staticCompositionLocalOf { false }

/**
 * A no-op replacement for Compose's ripple.
 *
 * On an LCD a ripple is a 300 ms fade nobody notices. On E Ink every frame of it is a panel
 * refresh, so a single tap flashes the whole screen and leaves ghosting behind.
 */
object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

