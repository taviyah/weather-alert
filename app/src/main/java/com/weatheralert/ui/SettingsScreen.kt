package com.weatheralert.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weatheralert.model.AqiScale
import com.weatheralert.model.TempUnit
import com.weatheralert.model.Thresholds
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Thresholds,
    onChange: (Thresholds) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExpandableSection("Display", if (settings.eInkMode) "E Ink mode on · ${unitLabel(settings.tempUnit)}" else "Standard colours · ${unitLabel(settings.tempUnit)}") {
                    ToggleRow("High contrast (E Ink)", settings.eInkMode) {
                        onChange(settings.copy(eInkMode = it, eInkDefaultApplied = true))
                    }
                    Note(
                        "Pure black on white, no shadows or animation, and severity shown by shape " +
                            "and label rather than by colour — red and amber are nearly the same " +
                            "shade of grey once an e-paper panel dithers them. Turned on " +
                            "automatically on e-paper devices; your choice here always wins.",
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipRow(
                        label = "Temperature unit",
                        options = TempUnit.entries.toList(),
                        selected = settings.tempUnit,
                        display = { if (it == TempUnit.CELSIUS) "°C" else "°F" },
                        onSelect = { onChange(settings.copy(tempUnit = it)) },
                    )
                }
            }

            item {
                ExpandableSection("General", "${settings.horizonDays} days ahead · checks every ${settings.checkEveryHours}h") {
                    StepperRow(
                        "Days to look ahead",
                        "${settings.horizonDays} days",
                        onDown = { onChange(settings.copy(horizonDays = settings.horizonDays - 1)) },
                        onUp = { onChange(settings.copy(horizonDays = settings.horizonDays + 1)) },
                        canGoDown = settings.horizonDays > 2,
                        canGoUp = settings.horizonDays < 7,
                    )
                    Note(
                        "Beyond about 5 days the forecast changes so often that alerts start " +
                            "contradicting each other. 3-4 is the sweet spot.",
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipRow(
                        label = "Check every",
                        options = listOf(1, 3, 6, 12),
                        selected = settings.checkEveryHours,
                        display = { "${it}h" },
                        onSelect = { onChange(settings.copy(checkEveryHours = it)) },
                    )
                    Note(
                        "The models behind this data are re-run about every 3 hours, but these " +
                            "alerts are about days 1-4 out, so catching an update a few hours " +
                            "later changes nothing. 6h is the default; 1h mostly re-reads the " +
                            "same numbers at twice the battery cost.",
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("Keep checks alive (permanent notification)", settings.keepAlive) {
                        onChange(settings.copy(keepAlive = it, keepAliveDefaultApplied = true))
                    }
                    Note(
                        "Some phones, MediaTek ones such as the Bigme HiBreak in particular, shut " +
                            "the app down minutes after you leave it, and no alert arrives until " +
                            "you open it again. This keeps a silent \"Watching the forecast\" " +
                            "notification running to stop that. Turned on automatically on such " +
                            "phones; elsewhere it only uses memory. Your choice here always wins.",
                    )
                }
            }

            item {
                ExpandableSection("Rain", summaryFor(settings.enableRain, "${fmt("%.1f", settings.rainMinMm)} mm at ${settings.rainProbPct}% · heavy ${fmt("%.0f", settings.rainHeavyMm)} mm")) {
                    ToggleRow("Alert on rain", settings.enableRain) {
                        onChange(settings.copy(enableRain = it))
                    }
                    if (settings.enableRain) {
                        DoubleStep("Minimum rain", settings.rainMinMm, 0.1, 5.0, 0.1,
                            { fmt("%.1f mm", it) }) { onChange(settings.copy(rainMinMm = it)) }
                        IntStep("Minimum chance", settings.rainProbPct, 10, 90, 5,
                            { "$it%" }) { onChange(settings.copy(rainProbPct = it)) }
                        DoubleStep("Heavy rain (major)", settings.rainHeavyMm, 3.0, 40.0, 1.0,
                            { fmt("%.0f mm", it) }) { onChange(settings.copy(rainHeavyMm = it)) }
                    }
                }
            }

            item {
                ExpandableSection("Dust", summaryFor(settings.enableDust, "PM10 ${settings.pm10Ugm3.roundToInt()} · heavy ${settings.pm10SevereUgm3.roundToInt()}")) {
                    ToggleRow("Alert on dust", settings.enableDust) {
                        onChange(settings.copy(enableDust = it))
                    }
                    if (settings.enableDust) {
                        DoubleStep("Dusty air above", settings.pm10Ugm3, 40.0, 300.0, 10.0,
                            { "PM10 ${it.roundToInt()}" }) { onChange(settings.copy(pm10Ugm3 = it)) }
                        DoubleStep("Heavy dust (major)", settings.pm10SevereUgm3, 80.0, 600.0, 10.0,
                            { "PM10 ${it.roundToInt()}" }) { onChange(settings.copy(pm10SevereUgm3 = it)) }
                        Note(
                            "During a khamsin, PM10 in Israel routinely runs 200-500 µg/m³. " +
                                "A clean coastal day sits around 20-40.",
                        )
                    }
                }
            }

            item {
                ExpandableSection("Air quality", summaryFor(settings.enableAirQuality, "${if (settings.aqiScale == AqiScale.EUROPEAN) "EU" else "US"} AQI above ${settings.aqiPoor}")) {
                    ToggleRow("Alert on poor air quality", settings.enableAirQuality) {
                        onChange(settings.copy(enableAirQuality = it))
                    }
                    if (settings.enableAirQuality) {
                        ChipRow(
                            label = "Scale",
                            options = AqiScale.entries.toList(),
                            selected = settings.aqiScale,
                            display = { if (it == AqiScale.EUROPEAN) "European" else "US EPA" },
                            onSelect = { onChange(settings.copy(aqiScale = it)) },
                        )
                        if (settings.aqiScale == AqiScale.EUROPEAN) {
                            IntStep("Alert above", settings.euAqiPoor, 40, 140, 5,
                                { "EU AQI $it" }) { onChange(settings.copy(euAqiPoor = it)) }
                            IntStep("Major alert above", settings.euAqiVeryPoor, 60, 200, 5,
                                { "EU AQI $it" }) { onChange(settings.copy(euAqiVeryPoor = it)) }
                            Note(
                                "The European scale calls 60 \"Poor\", but background ozone and " +
                                    "dust keep Israel in that band most days — Jerusalem's typical " +
                                    "daily peak is around 69. The default of 80 sits above that " +
                                    "baseline so an alert means something unusual.",
                            )
                        } else {
                            IntStep("Alert above", settings.usAqiPoor, 50, 200, 5,
                                { "US AQI $it" }) { onChange(settings.copy(usAqiPoor = it)) }
                            IntStep("Major alert above", settings.usAqiVeryPoor, 100, 300, 5,
                                { "US AQI $it" }) { onChange(settings.copy(usAqiVeryPoor = it)) }
                        }
                        Note(
                            "Air-quality alerts are suppressed on days that already trigger a " +
                                "dust alert, since dust is what drives the number up.",
                        )
                    }
                }
            }

            item {
                ExpandableSection("Temperature change", summaryFor(settings.enableTempChange, "${formatDelta(settings.tempNotableC, settings.tempUnit)} notable · ${formatDelta(settings.tempMajorC, settings.tempUnit)} sharp")) {
                    ToggleRow("Alert on sudden changes", settings.enableTempChange) {
                        onChange(settings.copy(enableTempChange = it))
                    }
                    if (settings.enableTempChange) {
                        DoubleStep(
                            "Notable change", settings.tempNotableC, 1.0, 12.0, 0.5,
                            { formatDelta(it, settings.tempUnit) },
                        ) {
                            onChange(
                                settings.copy(
                                    tempNotableC = it,
                                    tempMajorC = maxOf(settings.tempMajorC, it + 0.5),
                                ),
                            )
                        }
                        DoubleStep(
                            "Sharp change (major)", settings.tempMajorC, 1.5, 18.0, 0.5,
                            { formatDelta(it, settings.tempUnit) },
                        ) {
                            onChange(
                                settings.copy(
                                    tempMajorC = it,
                                    tempNotableC = minOf(settings.tempNotableC, it - 0.5),
                                ),
                            )
                        }
                        ToggleRow("Also use feels-like", settings.useApparentTemp) {
                            onChange(settings.copy(useApparentTemp = it))
                        }
                        Note(
                            "Compares each day's high against the previous day's high, and each " +
                                "night's low against the previous night's low. Across five weather " +
                                "models the day-over-day change agrees to about 1°, so 3° is a real " +
                                "signal rather than a difference of opinion between forecasts.",
                        )
                    }
                }
            }

            item {
                ExpandableSection("Wind change", summaryFor(settings.enableWindChange, "${settings.windNotableKmh.roundToInt()} km/h notable · ${settings.windMajorKmh.roundToInt()} sharp")) {
                    ToggleRow("Alert on sudden wind changes", settings.enableWindChange) {
                        onChange(settings.copy(enableWindChange = it))
                    }
                    if (settings.enableWindChange) {
                        DoubleStep(
                            "Notable change", settings.windNotableKmh, 5.0, 30.0, 1.0,
                            { "${it.roundToInt()} km/h" },
                        ) {
                            onChange(
                                settings.copy(
                                    windNotableKmh = it,
                                    windMajorKmh = maxOf(settings.windMajorKmh, it + 1),
                                ),
                            )
                        }
                        DoubleStep(
                            "Sharp change (major)", settings.windMajorKmh, 8.0, 45.0, 1.0,
                            { "${it.roundToInt()} km/h" },
                        ) {
                            onChange(
                                settings.copy(
                                    windMajorKmh = it,
                                    windNotableKmh = minOf(settings.windNotableKmh, it - 1),
                                ),
                            )
                        }
                        DoubleStep("Ignore unless gusts reach", settings.windMinKmh, 15.0, 70.0, 5.0,
                            { "${it.roundToInt()} km/h" }) { onChange(settings.copy(windMinKmh = it)) }
                        Note(
                            "Measured over 90 days in five Israeli cities, gust changes run a " +
                                "median of 3 km/h and reach 13 only in the top 5% of days — so 12 " +
                                "is already an unusual day, and 20+ would almost never fire.",
                        )
                    }
                }
            }

            item {
                ExpandableSection("Extremes", summaryFor(settings.enableExtremes, "hot ${formatAbs(settings.heatC, settings.tempUnit)} · cold ${formatAbs(settings.coldC, settings.tempUnit)}")) {
                    ToggleRow("Hot days, cold nights and frost", settings.enableExtremes) {
                        onChange(settings.copy(enableExtremes = it))
                    }
                    if (settings.enableExtremes) {
                        DoubleStep("Hot day above", settings.heatC, 25.0, 45.0, 1.0,
                            { formatAbs(it, settings.tempUnit) }) { onChange(settings.copy(heatC = it)) }
                        DoubleStep("Extreme heat above", settings.heatSevereC, 30.0, 50.0, 1.0,
                            { formatAbs(it, settings.tempUnit) }) { onChange(settings.copy(heatSevereC = it)) }
                        DoubleStep("Cold night below", settings.coldC, -5.0, 18.0, 1.0,
                            { formatAbs(it, settings.tempUnit) }) { onChange(settings.copy(coldC = it)) }
                        Note(
                            "These fire only when the forecast crosses the line, not on every day " +
                                "of a heat wave.",
                        )
                    }
                }
            }

            item {
                ExpandableSection("Advice signals", adviceSummary(settings)) {
                    Note(
                        "Plain-language verdicts from the same forecast, shown per day. Only a " +
                            "\"NO\" verdict sends a notification; \"!\" stays in the app.",
                    )
                    Spacer(Modifier.height(10.dp))

                    ToggleRow("🧺  Laundry signal", settings.enableLaundry) {
                        onChange(settings.copy(enableLaundry = it))
                    }
                    if (settings.enableLaundry) {
                        DoubleStep("Dust that dulls whites", settings.laundryDustCautionPm10, 30.0, 150.0, 5.0,
                            { "PM10 ${it.roundToInt()}" }) { onChange(settings.copy(laundryDustCautionPm10 = it)) }
                        DoubleStep("Dust that means do not hang", settings.laundryDustBadPm10, 60.0, 300.0, 10.0,
                            { "PM10 ${it.roundToInt()}" }) { onChange(settings.copy(laundryDustBadPm10 = it)) }
                        IntStep("Rain chance to not hang", settings.laundryRainProbPct, 20, 80, 5,
                            { "$it%" }) { onChange(settings.copy(laundryRainProbPct = it)) }
                        IntStep("Humidity too high to dry", settings.laundryHumidityPct, 60, 95, 5,
                            { "$it%" }) { onChange(settings.copy(laundryHumidityPct = it)) }
                        Note(
                            "Set below the health thresholds on purpose. Clean air here is PM10 " +
                                "24-28, and wet fabric collects dust efficiently, so whites start " +
                                "to grey long before the air is unhealthy to breathe.",
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    ToggleRow("😷  Mask signal", settings.enableMask) {
                        onChange(settings.copy(enableMask = it))
                    }
                    if (settings.enableMask) {
                        DoubleStep("Mask if sensitive", settings.maskPm25Caution, 10.0, 60.0, 5.0,
                            { "PM2.5 ${it.roundToInt()}" }) { onChange(settings.copy(maskPm25Caution = it)) }
                        DoubleStep("Everyone should mask", settings.maskPm25Bad, 25.0, 150.0, 5.0,
                            { "PM2.5 ${it.roundToInt()}" }) { onChange(settings.copy(maskPm25Bad = it)) }
                        Note(
                            "Driven by particulates only, never by the headline AQI. A mask filters " +
                                "particles but does nothing for ozone or NO₂ — and those drive " +
                                "almost every high-AQI day here. On a gas day this signal says so " +
                                "instead of recommending a mask that will not help.",
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    ToggleRow("🧥  Clothing signal", settings.enableClothing) {
                        onChange(settings.copy(enableClothing = it))
                    }
                    if (settings.enableClothing) {
                        IntStep("Rain chance for a raincoat", settings.clothingRainProbPct, 20, 80, 5,
                            { "$it%" }) { onChange(settings.copy(clothingRainProbPct = it)) }
                        DoubleStep("Wind for a windproof layer", settings.clothingWindGustKmh, 30.0, 90.0, 5.0,
                            { "${it.roundToInt()} km/h" }) { onChange(settings.copy(clothingWindGustKmh = it)) }
                        DoubleStep("Cool evening below", settings.clothingColdEveningC, 2.0, 20.0, 1.0,
                            { formatAbs(it, settings.tempUnit) }) { onChange(settings.copy(clothingColdEveningC = it)) }
                        Note(
                            "Phrased relative to yesterday, which is how people actually dress, and " +
                                "measured on feels-like rather than the raw reading.",
                        )
                    }
                }
            }

            item {
                ExpandableSection("Troubleshooting", "Re-send alerts you already dismissed") {
                    Text(
                        "Each alert is shown once. This clears that record so the next check " +
                            "re-sends everything currently in the forecast.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onResend,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("Re-send current alerts") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- stepper plumbing


private fun unitLabel(u: TempUnit) = if (u == TempUnit.CELSIUS) "°C" else "°F"

private fun summaryFor(enabled: Boolean, detail: String) = if (enabled) detail else "Off"

private fun adviceSummary(t: Thresholds): String {
    val on = listOfNotNull(
        "Laundry".takeIf { t.enableLaundry },
        "Mask".takeIf { t.enableMask },
        "Clothing".takeIf { t.enableClothing },
    )
    return if (on.isEmpty()) "All off" else on.joinToString(", ")
}

@Composable
private fun DoubleStep(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    format: (Double) -> String,
    onChange: (Double) -> Unit,
) {
    // Snapping to the step keeps a value like 5.5 from drifting to 5.4999999 after a few taps.
    fun snap(v: Double) = Math.round(v / step) * step
    StepperRow(
        label = label,
        value = format(value),
        onDown = { onChange(snap((value - step).coerceAtLeast(min))) },
        onUp = { onChange(snap((value + step).coerceAtMost(max))) },
        canGoDown = value > min,
        canGoUp = value < max,
    )
}

@Composable
private fun IntStep(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    StepperRow(
        label = label,
        value = format(value),
        onDown = { onChange((value - step).coerceAtLeast(min)) },
        onUp = { onChange((value + step).coerceAtMost(max)) },
        canGoDown = value > min,
        canGoUp = value < max,
    )
}

private fun fmt(pattern: String, v: Double) = String.format(Locale.US, pattern, v)

/** Half-degree steps matter now that the defaults are 3 and 5.5, so whole numbers are not enough. */
private fun formatDelta(celsius: Double, unit: TempUnit): String {
    val v = if (unit == TempUnit.FAHRENHEIT) celsius * 9 / 5 else celsius
    val unitText = if (unit == TempUnit.FAHRENHEIT) "°F" else "°C"
    return if (v % 1.0 == 0.0) "${v.roundToInt()} $unitText"
    else String.format(Locale.US, "%.1f %s", v, unitText)
}

private fun formatAbs(celsius: Double, unit: TempUnit): String =
    if (unit == TempUnit.FAHRENHEIT) "${(celsius * 9 / 5 + 32).roundToInt()} °F"
    else "${celsius.roundToInt()} °C"

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            display(option),
                            fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}
