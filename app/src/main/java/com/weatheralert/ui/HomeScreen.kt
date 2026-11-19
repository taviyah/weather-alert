package com.weatheralert.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatheralert.domain.DayDigest
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.DayAdvice
import com.weatheralert.model.Severity
import com.weatheralert.model.TempUnit
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WeatherAlert
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    refreshing: Boolean,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: (String) -> Unit,
    onFixBattery: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onOpen: (String) -> Unit,
    onHere: () -> Unit,
) {
    val today = LocalDate.now()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // Moving and removing places are rare jobs, so their controls stay hidden until "Edit".
    var editing by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The drag reorders this local copy so the row follows the finger without a disk write per
    // swap; the result is persisted once on release. It clears as soon as the stored order
    // agrees, so a later change from elsewhere is not masked by a stale copy.
    var localOrder by remember { mutableStateOf<List<String>?>(null) }
    val incomingIds = state.cards.map { it.location.id }
    LaunchedEffect(incomingIds, localOrder) {
        if (localOrder != null && localOrder == incomingIds) localOrder = null
    }
    val cards = remember(state.cards, localOrder) {
        val order = localOrder ?: return@remember state.cards
        state.cards.sortedBy { c ->
            order.indexOf(c.location.id).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }

    fun currentIds() = localOrder ?: cards.map { it.location.id }

    val reorder = rememberReorderState(
        listState = listState,
        scope = scope,
        draggableKeys = { cards.map { it.location.id }.toSet() },
        onMove = { from, to ->
            val ids = currentIds().toMutableList()
            // The lazy list also holds banners and a footer, so list indices are not card indices.
            val info = listState.layoutInfo.visibleItemsInfo
            val fromKey = info.firstOrNull { it.index == from }?.key as? String
            val toKey = info.firstOrNull { it.index == to }?.key as? String
            val a = ids.indexOf(fromKey)
            val b = ids.indexOf(toKey)
            if (a >= 0 && b >= 0 && a != b) {
                ids.add(b, ids.removeAt(a))
                localOrder = ids
            }
        },
        onCommit = { onReorder(currentIds()) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "Edit places" else "Weather Alert") },
                actions = {
                    if (editing) {
                        TextButton(onClick = { editing = false }) { Text("Done") }
                        return@TopAppBar
                    }
                    if (state.cards.isNotEmpty()) {
                        IconButton(onClick = { editing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit places: reorder or remove")
                        }
                    }
                    IconButton(onClick = onHere) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Forecast where you are")
                    }
                    // A spinner is an endless animation; on e-paper every frame is a panel
                    // refresh, so the busy state is a word instead.
                    if (refreshing) {
                        BusyLabel()
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!editing) ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add place") },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.showBatteryWarning) {
                item(key = "banner_battery") {
                    WarningBanner(
                        title = "Alerts may not arrive",
                        body = "Android moves apps you rarely open into a restricted bucket and " +
                            "defers their background work to about once a day. This app is one " +
                            "you are never meant to open, so it will end up there. Exempting it " +
                            "from battery optimisation is the one thing that reliably prevents it.",
                        actionLabel = "Allow background checks",
                        onAction = onFixBattery,
                    )
                }
            }

            if (state.stalled) {
                item(key = "banner_stalled") {
                    WarningBanner(
                        title = "No check for ${state.hoursSinceLastRun} hours",
                        body = "The last successful check was ${state.hoursSinceLastRun} hours ago, " +
                            "well past the ${state.settings.checkEveryHours}-hour schedule. The " +
                            "system is probably holding the app back, or the network has been " +
                            "unavailable.",
                        actionLabel = if (state.batteryExempt) null else "Allow background checks",
                        onAction = if (state.batteryExempt) null else onFixBattery,
                    )
                }
            }

            if (state.loaded && state.cards.isEmpty()) {
                item(key = "empty") { EmptyState() }
            }

            itemsIndexed(cards, key = { _, c -> c.location.id }) { index, card ->
                val dragging = reorder.draggingKey == card.location.id
                LocationCardView(
                    card = card,
                    unit = state.settings.tempUnit,
                    thresholds = state.settings,
                    today = today,
                    editing = editing,
                    reorderable = cards.size > 1,
                    dragging = dragging,
                    canMoveUp = index > 0,
                    canMoveDown = index < cards.lastIndex,
                    onMoveUp = {
                        val ids = currentIds().toMutableList()
                        ids.add(index - 1, ids.removeAt(index))
                        localOrder = ids
                        onReorder(ids)
                    },
                    onMoveDown = {
                        val ids = currentIds().toMutableList()
                        ids.add(index + 1, ids.removeAt(index))
                        localOrder = ids
                        onReorder(ids)
                    },
                    onRemove = { pendingDelete = card.location.id },
                    onOpen = { onOpen(card.location.id) },
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) reorder.dragOffset else 0f }
                        .reorderableItem(reorder, card.location.id),
                )
            }
            // Not before the places load: a lazy list keeps its first visible item in view, so a
            // footer that is alone on the first frame would hold the list scrolled to the bottom
            // once the cards arrive above it.
            if (state.loaded) {
                item(key = "footer") { LastCheckedFooter(state) }
            }
        }
    }

    pendingDelete?.let { id ->
        val name = state.cards.firstOrNull { it.location.id == id }?.location?.name ?: "this place"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Stop watching $name?") },
            text = { Text("Its forecast and alert history are removed from the device.") },
            confirmButton = {
                TextButton(onClick = { onRemove(id); pendingDelete = null }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌦", fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text("No places watched yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add a town or city and this app will warn you when rain, dust, poor air " +
                    "quality or a sharp change in temperature or wind is coming.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocationCardView(
    card: LocationCard,
    unit: TempUnit,
    thresholds: Thresholds,
    today: LocalDate,
    editing: Boolean,
    reorderable: Boolean,
    dragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    AppCard(modifier) {
        // A long press still starts the reorder drag: the drag consumes the pointer, which
        // cancels this click.
        Column(Modifier.clickable(enabled = !editing, onClick = onOpen).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reorderable && editing) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Long-press and drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        card.location.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (dragging) FontWeight.Black else FontWeight.Bold,
                    )
                    if (card.location.subtitle.isNotEmpty()) {
                        Text(
                            card.location.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Dragging repaints the whole panel on every pointer event on e-paper, so that
                // mode also gets discrete taps. The long-press drag still works either way.
                if (editing) {
                    if (reorderable && eInk) {
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                        }
                    }
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Details",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val days = card.forecast?.days.orEmpty()
                .filter { !it.localDate.isBefore(today) }
                .take(5)

            if (days.isEmpty()) {
                Text(
                    "Waiting for the first forecast…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    days.forEach { day ->
                        val air = card.forecast?.airFor(day.localDate)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                day.localDate.relativeLabel(today),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(weatherEmoji(day.weatherCode), fontSize = 22.sp)
                            Text(
                                day.tempMax.asTemp(unit),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                day.tempMin.asTemp(unit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (day.precipMm > 0.05) {
                                Text(
                                    String.format(Locale.US, "%.1fmm", day.precipMm),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            // Plain words, only on days that need them; the numbers are on the detail screen.
                            airLabel(air?.pm10Max, air?.dustMax, thresholds)?.let { (label, severe) ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (severe) FontWeight.Bold else FontWeight.SemiBold,
                                )
                            }
                            val flags = card.adviceFor(day.localDate)
                                .filter { it.level != AdviceLevel.GOOD }
                            if (flags.isNotEmpty()) {
                                Spacer(Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    flags.forEach { a ->
                                        Text(
                                            a.kind.emoji(),
                                            fontSize = 11.sp,
                                            // A BAD verdict is solid; a caution is faded. On
                                            // e-paper the alpha difference survives where a hue
                                            // change would not.
                                            modifier = Modifier.alpha(
                                                if (a.level == AdviceLevel.BAD) 1f else 0.45f,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val soon = (0L..1L)
                .flatMap { card.adviceFor(today.plusDays(it)) }
                .filter { it.level != AdviceLevel.GOOD }
            if (soon.isNotEmpty() || card.alerts.isNotEmpty()) {
                // Folded by default: with three places and a wet week the full lists ran to
                // a dozen rows each and buried the forecast strip. The header keeps the counts,
                // the kinds and whether anything is major, so nothing important hides. Saved per
                // place so it survives a trip to the detail screen and back.
                // The soonest day is always shown, so the card says what is coming without a tap;
                // the rest fold behind "N more days" so a wet week doesn't bury the forecast strip.
                var expanded by rememberSaveable(card.location.id) { mutableStateOf(false) }
                val digest = DayDigest.build(card.alerts, soon)
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                // No AnimatedVisibility: on e-paper every animation frame is a panel refresh.
                (if (expanded) digest else digest.take(1)).forEach { DigestRow(it, today) }
                if (digest.size > 1) {
                    MoreToggle(rest = digest.drop(1), expanded = expanded) { expanded = !expanded }
                }
            }
        }
    }
}

/**
 * "2 more days (1 major)" under the first alert day, or "Show less" when open. Its own click
 * target, so a tap here toggles the list instead of opening the detail screen.
 */
@Composable
private fun MoreToggle(rest: List<DayDigest>, expanded: Boolean, onToggle: () -> Unit) {
    val major = rest.count { it.major }
    val label = if (expanded) "Show less" else
        countOf(rest.size, "more day") + if (major > 0) " ($major major)" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide alerts" else "Show alerts", onClick = onToggle)
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (major > 0 && !expanded) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

/** "Dusty" / "Very dusty" at the same bars the dust alert uses; null on a normal day. */
private fun airLabel(pm10: Double?, dust: Double?, t: Thresholds): Pair<String, Boolean>? {
    val severe = (pm10 != null && pm10 >= t.pm10SevereUgm3) || (dust != null && dust >= t.dustSevereUgm3)
    val notable = (pm10 != null && pm10 >= t.pm10Ugm3) || (dust != null && dust >= t.dustUgm3)
    return when {
        severe -> "Very dusty" to true
        notable -> "Dusty" to false
        else -> null
    }
}

private fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * A whole day in one entry: "Tomorrow · 🌧 Heavy rain 12 mm (70%) · 💨 Windier, gusts 25→60 km/h"
 * with the tips underneath, minus whatever reason the alerts already gave. A day with only tips
 * puts them on the first line instead. The full per-alert detail stays on the detail screen.
 */
@Composable
private fun DigestRow(d: DayDigest, today: LocalDate) {
    val facts = d.facts.joinToString(" · ") { f ->
        "${f.kind.emoji()} ${f.text.replaceFirstChar { it.uppercase() }}"
    }
    val tips = d.tips.joinToString(" · ") { t ->
        "${t.kind.emoji()} ${t.action}" + (t.reason?.let { " ($it)" } ?: "")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (d.facts.isNotEmpty()) {
            SeverityMark(if (d.major) Severity.MAJOR else Severity.NOTABLE, Modifier.padding(top = 2.dp))
        } else {
            AdviceMark(d.worstAdvice ?: AdviceLevel.CAUTION, Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${d.date.relativeLabel(today)} · ${facts.ifEmpty { tips }}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (d.major || d.worstAdvice == AdviceLevel.BAD) FontWeight.Bold
                else FontWeight.Normal,
            )
            if (facts.isNotEmpty() && tips.isNotEmpty()) {
                Text(
                    tips,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun AdviceRow(advice: DayAdvice, today: LocalDate) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AdviceMark(advice.level, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text("${advice.kind.emoji()} ", fontSize = 15.sp)
        Column(Modifier.weight(1f)) {
            Text(
                "${advice.localDate.relativeLabel(today)} · ${advice.headline}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (advice.level == AdviceLevel.BAD) FontWeight.Bold
                else FontWeight.Normal,
            )
            Text(
                advice.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AlertRow(alert: WeatherAlert, today: LocalDate) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SeverityMark(alert.severity, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text("${alert.kind.emoji()} ", fontSize = 15.sp)
        Column(Modifier.weight(1f)) {
            Text(
                "${alert.localDate.relativeLabel(today)} · ${alert.headline}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (alert.severity == Severity.MAJOR) FontWeight.Bold
                else FontWeight.Normal,
            )
            Text(
                alert.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LastCheckedFooter(state: HomeState) {
    if (state.lastRun.epochMs == 0L) return
    val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - state.lastRun.epochMs)
    val ago = when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / 1440} d ago"
    }
    Text(
        "Checked $ago" + (state.lastRun.error?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
