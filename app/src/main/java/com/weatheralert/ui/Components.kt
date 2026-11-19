package com.weatheralert.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.Severity

/**
 * A card that reads correctly on both display types: elevation on an LCD, a hard border on
 * e-paper, where a drop shadow is just a band of dither noise.
 */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val eInk = LocalEInk.current
    if (eInk) {
        Card(
            modifier = modifier,
            elevation = CardDefaults.cardElevation(0.dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) { content() }
    } else {
        Card(modifier = modifier, elevation = CardDefaults.cardElevation(2.dp)) { content() }
    }
}

/**
 * Severity as a *shape*, not a hue.
 *
 * Red #C62828 and amber #E08700 differ by 59 points of greyscale luminance, which survives an LCD
 * and does not survive E Ink dithering. A filled square versus a hollow one survives anything, so
 * that carries the meaning and colour is only ever a bonus on top.
 */
@Composable
fun SeverityMark(severity: Severity, modifier: Modifier = Modifier) {
    val eInk = LocalEInk.current
    val major = severity == Severity.MAJOR
    val ink = if (eInk) MaterialTheme.colorScheme.onSurface else severityColor(severity)

    Box(
        modifier
            .size(width = 14.dp, height = 30.dp)
            .clip(RoundedCornerShape(3.dp))
            .then(
                if (major) Modifier.background(ink)
                else Modifier.border(2.dp, ink, RoundedCornerShape(3.dp)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (major) "!!" else "!",
            color = if (major) MaterialTheme.colorScheme.surface else ink,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Same idea for advice: a word plus a border weight, never colour alone. */
@Composable
fun AdviceMark(level: AdviceLevel, modifier: Modifier = Modifier) {
    val eInk = LocalEInk.current
    val ink = if (eInk) MaterialTheme.colorScheme.onSurface else adviceColor(level)
    val label = when (level) {
        AdviceLevel.GOOD -> "OK"
        AdviceLevel.CAUTION -> "!"
        AdviceLevel.BAD -> "NO"
    }
    Box(
        modifier
            .size(width = 26.dp, height = 20.dp)
            .clip(RoundedCornerShape(3.dp))
            .then(
                if (level == AdviceLevel.BAD) Modifier.background(ink)
                else Modifier.border(if (level == AdviceLevel.CAUTION) 2.dp else 1.dp, ink, RoundedCornerShape(3.dp)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (level == AdviceLevel.BAD) MaterialTheme.colorScheme.surface else ink,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Replaces every threshold slider in the app.
 *
 * Dragging a slider on e-paper is a stream of full-panel refreshes for a value you cannot read
 * until you let go. Discrete taps are also simply better for this job on any screen: these are
 * numbers people want to set exactly, like 5.5 °C, not fractions of a bar.
 */
@Composable
fun StepperRow(
    label: String,
    value: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    canGoDown: Boolean = true,
    canGoUp: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        StepButton("−", onDown, canGoDown)
        Text(
            value,
            Modifier.width(96.dp).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        StepButton("+", onUp, canGoUp)
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit, enabled: Boolean) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(glyph, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** A warning strip. On e-paper it is a heavy black border rather than a wash of colour. */
@Composable
fun WarningBanner(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val eInk = LocalEInk.current
    val accent = if (eInk) MaterialTheme.colorScheme.onSurface else Color(0xFFC62828)
    Card(
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(if (eInk) 2.5.dp else 1.5.dp, accent),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onAction,
                    border = BorderStroke(1.5.dp, accent),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(actionLabel, color = accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * A determinate, text-only "working" indicator. Compose's spinner is an endless animation, which
 * on e-paper means the panel never stops refreshing while a fetch is in flight.
 */
@Composable
fun BusyLabel(text: String = "Checking…") {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * A settings group that starts closed.
 *
 * The screen carries around thirty tunable numbers; as one flat list it was a long scroll to
 * reach anything. Closed sections still show [summary], so the current setting is readable
 * without opening them.
 *
 * Deliberately not animated: an expand animation on e-paper is a burst of full-panel refreshes,
 * and the content is a fixed height anyway.
 */
@Composable
fun ExpandableSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    AppCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!expanded && summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
fun ChipRowSpacing() = Arrangement.spacedBy(8.dp)
