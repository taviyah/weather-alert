package com.weatheralert.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatheralert.domain.Insights
import com.weatheralert.model.AqiScale
import com.weatheralert.model.DayAir
import com.weatheralert.model.DayWeather
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Thresholds
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything known about one place, one day at a time.
 *
 * Days are picked from a row of tabs rather than stacked into one long scroll: on e-paper a long
 * scroll is a long run of panel refreshes, and the question is nearly always about one day.
 * Nothing here animates, and every chart is a static drawing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationDetailScreen(
    card: LocationCard?,
    settings: Thresholds,
    onBack: () -> Unit,
    /** Shown instead of the forecast while there is none, e.g. "Finding where you are…". */
    emptyMessage: String = "Waiting for the first forecast…",
    /** The current-location view: labels the place as "you are here". */
    isHere: Boolean = false,
    /** Offers to start watching the shown place; null when it is already watched. */
    onWatch: (() -> Unit)? = null,
) {
    val today = card?.location?.today() ?: LocalDate.now()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            card?.location?.name ?: if (isHere) "Where you are" else "",
                            fontWeight = FontWeight.Bold,
                        )
                        val sub = listOfNotNull(
                            "📍 You are here".takeIf { isHere },
                            card?.location?.subtitle?.takeIf { it.isNotEmpty() },
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // A word rather than an icon: on e-paper a labelled button is unambiguous.
                    if (onWatch != null && card != null) {
                        TextButton(onClick = onWatch) { Text("Watch") }
                    }
                },
            )
        },
    ) { padding ->
        val forecast = card?.forecast
        val days = forecast?.days.orEmpty().filter { !it.localDate.isBefore(today) }
        if (card == null || forecast == null || days.isEmpty()) {
            Text(
                emptyMessage,
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }

        var selected by rememberSaveable(card.location.id) { mutableIntStateOf(0) }
        val day = days[selected.coerceIn(days.indices)]
        val date = day.localDate

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DayPicker(days, selected, today, settings) { selected = it }

            OverviewSection(day, settings)
            RainSection(forecast, day)
            AirSection(forecast.airFor(date), forecast, date, settings)
            HumiditySection(forecast, day, settings)
            SunWindSection(day)

            val advice = card.adviceFor(date).filter { it.level != com.weatheralert.model.AdviceLevel.GOOD }
            val alerts = card.alerts.filter { it.localDate == date }
            if (advice.isNotEmpty() || alerts.isNotEmpty()) {
                SectionCard("Alerts and advice") {
                    advice.forEach { AdviceRow(it, today) }
                    alerts.forEach { AlertRow(it, today) }
                }
            }

            Text(
                "Air-quality forecasts reach fewer days ahead than the weather. Rain times are the hours with at " +
                    "least a 40% chance or 0.2 mm forecast.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun DayPicker(
    days: List<DayWeather>,
    selected: Int,
    today: LocalDate,
    settings: Thresholds,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.take(7).forEachIndexed { i, d ->
            val on = i == selected
            val ink = MaterialTheme.colorScheme.onSurface
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(if (on) 2.5.dp else 1.dp, if (on) ink else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    d.localDate.relativeLabel(today).let { if (it == "Tomorrow") "Tmrw" else it },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (on) FontWeight.Black else FontWeight.Normal,
                )
                Text(weatherEmoji(d.weatherCode), fontSize = 18.sp)
                Text(
                    "${d.tempMax.asTemp(settings.tempUnit)} ${d.tempMin.asTemp(settings.tempUnit)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun OverviewSection(day: DayWeather, settings: Thresholds) {
    val unit = settings.tempUnit
    SectionCard("${day.localDate.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(weatherEmoji(day.weatherCode), fontSize = 34.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(weatherLabel(day.weatherCode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "High ${day.tempMax.asTemp(unit)} · low ${day.tempMin.asTemp(unit)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Feels like ${day.apparentMax.asTemp(unit)} / ${day.apparentMin.asTemp(unit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RainSection(forecast: LocationForecast, day: DayWeather) {
    val date = day.localDate
    val windows = Insights.rainWindows(forecast.hours, date)
    val slots = Insights.rainHours(forecast.hours, date)
    SectionCard("🌧 Rain") {
        val likeliest = slots.maxByOrNull { it.probPct }
        when {
            slots.isEmpty() -> Body(
                if (day.precipProbPct >= 40) "${day.precipProbPct}% chance, ${mm(day.precipMm)} in total. " +
                    "Hourly timing arrives with the next refresh."
                else "No rain expected.",
            )
            // The daily total can show a trace that no single hour reaches the bar for. Saying
            // "no rain" under a strip that reads 0.2 mm looked like a contradiction.
            windows.isEmpty() && (day.precipMm >= 0.1 || (likeliest?.probPct ?: 0) >= 20) -> Body(
                "Unlikely. At most a ${likeliest?.probPct ?: day.precipProbPct}% chance in any hour" +
                    (likeliest?.takeIf { it.probPct > 0 }?.let { ", around ${hhmm(it.hour)}" } ?: "") +
                    (if (day.precipMm >= 0.1) "; a trace of ${mm(day.precipMm)} at most." else "."),
            )
            windows.isEmpty() -> Body("No rain expected.")
            else -> {
                Body(
                    "${day.precipProbPct}% chance, about ${mm(day.precipMm)} in total.",
                    bold = true,
                )
                Spacer(Modifier.height(4.dp))
                windows.forEach { w ->
                    Body(
                        "${hourSpan(w.startHour, w.endHour)} · ${w.intensity} · up to ${w.maxProbPct}%" +
                            (if (w.totalMm >= 0.1) " · ${mm(w.totalMm)}" else ""),
                    )
                }
            }
        }
        if (slots.any { it.probPct >= 20 || it.mm >= 0.1 }) {
            Spacer(Modifier.height(10.dp))
            HourBars(
                values = (0..23).map { h -> slots.firstOrNull { it.hour == h }?.probPct?.toFloat() },
                max = 100f,
                strong = { h -> (slots.firstOrNull { it.hour == h }?.mm ?: 0.0) >= 0.2 },
                threshold = 40f,
            )
            Caption("Bar height is the chance of rain. ■ Black: rain expected (0.2 mm or more). □ White: a chance, but little or no rain. Dashed line: 40%.")
        }
        val now = java.time.LocalDateTime.now()
        val fromHour = if (date == now.toLocalDate()) now.hour else 0
        val table = Insights.rainTable(forecast.hours, date, fromHour)
        if (table.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            RainTable(table, fromHour)
        }
        if (slots.isNotEmpty()) {
            Caption(
                if (forecast.rainSource == "ecmwf_ifs") "Rain from ECMWF; chance from its 51-run ensemble."
                else "Rain from the default model; ECMWF was unavailable at the last refresh.",
            )
        }
    }
}

@Composable
private fun AirSection(air: DayAir?, forecast: LocationForecast, date: LocalDate, settings: Thresholds) {
    val scale = settings.aqiScale
    val headline = air?.let { if (scale == AqiScale.EUROPEAN) it.euAqiMax else it.usAqiMax }
    SectionCard("😷 Air quality") {
        if (air == null || headline == null) {
            Body("No air-quality forecast for this day yet.")
            return@SectionCard
        }
        val band = Insights.aqiBand(headline, scale)
        AqiMeter(band.level)
        Spacer(Modifier.height(6.dp))
        Text(
            "${band.label} · ${if (scale == AqiScale.EUROPEAN) "European" else "US"} AQI $headline at worst",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Body(band.meaning)

        val hours = forecast.airHoursFor(date)
        Insights.airTiming(hours, scale)?.let { t ->
            Spacer(Modifier.height(4.dp))
            Body(
                "Worst around ${hhmm(t.worst.hour)} (${t.worst.aqi(scale)})" +
                    (t.cleanest?.let { ", cleanest around ${hhmm(it.hour)} (${it.aqi(scale)})" } ?: "") + ".",
            )
        }

        val pollutants = Insights.pollutants(air)
        if (pollutants.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            val top = pollutants.first()
            if (band.level >= 2) {
                Body(
                    "Mostly ${top.phrase}." +
                        if (top.isGas) " A mask filters particles, not gases, so it would not help with this." else "",
                    bold = true,
                )
                Spacer(Modifier.height(4.dp))
            }
            pollutants.forEach { p ->
                val reading = when (p.name) {
                    "Fine particles (PM2.5)" -> air.pm25Max?.let { " · ${it.roundToInt()} µg/m³" }
                    "Coarse particles (PM10)" -> air.pm10Max?.let { " · ${it.roundToInt()} µg/m³" }
                    else -> null
                }.orEmpty()
                PollutantRow(p.name, Insights.aqiBand(p.subIndex, AqiScale.EUROPEAN).label, reading)
            }
            air.dustMax?.let {
                PollutantRow(
                    "Desert dust",
                    if (it >= settings.dustUgm3) "Dusty" else if (it >= 25) "Hazy" else "Low",
                    " · ${it.roundToInt()} µg/m³",
                )
            }
        }

        if (hours.any { it.aqi(scale) != null }) {
            Spacer(Modifier.height(10.dp))
            HourBars(
                values = (0..23).map { h -> hours.firstOrNull { it.hour == h }?.aqi(scale)?.toFloat() },
                max = maxOf(if (scale == AqiScale.EUROPEAN) 100f else 200f, hours.maxOf { (it.aqi(scale) ?: 0).toFloat() }),
                strong = { h -> (hours.firstOrNull { it.hour == h }?.aqi(scale) ?: 0) >= settings.aqiPoor },
                threshold = settings.aqiPoor.toFloat(),
            )
            Caption("AQI by hour. The dashed line is your alert level (${settings.aqiPoor}); bars above it are solid.")
        }
    }
}

@Composable
private fun HumiditySection(forecast: LocationForecast, day: DayWeather, settings: Thresholds) {
    SectionCard("💧 Humidity") {
        val lo = day.humidityMinPct
        val hi = day.humidityMaxPct
        val range = when {
            lo != null && hi != null -> "$lo–$hi%"
            day.humidityMeanPct != null -> "about ${day.humidityMeanPct}%"
            else -> null
        }
        if (range == null) {
            Body("No humidity data for this day.")
            return@SectionCard
        }
        val feel = day.dewPointMaxC?.let(Insights::mugginess)
        Text(
            "$range" + (feel?.let { " · ${it.label}" } ?: ""),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        feel?.let {
            Body("${it.meaning} Dew point up to ${day.dewPointMaxC!!.asTemp(settings.tempUnit)}.")
        }
        Insights.humidityTiming(forecast.hours, day.localDate)?.let { (wet, dry) ->
            Body("Dampest around ${hhmm(wet.hour)} (${wet.humidityPct}%), driest around ${hhmm(dry.hour)} (${dry.humidityPct}%).")
        }
        if ((day.humidityMeanPct ?: 0) >= settings.laundryHumidityPct) {
            Body("Washing will dry slowly outdoors.")
        }
    }
}

@Composable
private fun SunWindSection(day: DayWeather) {
    SectionCard("☀ Sun and wind") {
        // UV is published and understood as a whole number; banding the raw 5.6 printed
        // "UV 6 · Moderate" on one day beside "UV 6 · High" on the next.
        day.uvIndexMax?.roundToInt()?.let { uv ->
            val band = Insights.uvBand(uv.toDouble())
            Body("UV $uv · ${band.label}. ${band.advice}", bold = uv >= 6)
        }
        val rise = day.sunrise?.substringAfter('T')
        val set = day.sunset?.substringAfter('T')
        if (rise != null && set != null) Body("Sunrise $rise · sunset $set")
        Body(
            "Wind up to ${day.windMaxKmh.roundToInt()} km/h, gusts ${day.gustMaxKmh.roundToInt()} km/h" +
                (day.windDirectionDeg?.let { ", mostly from the ${Insights.compass(it)}" } ?: "") + ".",
        )
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Body(text: String, bold: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PollutantRow(name: String, label: String, reading: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            label + reading,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Six steps, filled up to the band. Position carries the meaning, so it reads the same in black
 * and white; on an LCD the filled steps also take the band's conventional colour.
 */
@Composable
private fun AqiMeter(level: Int) {
    val eInk = LocalEInk.current
    val ink = MaterialTheme.colorScheme.onSurface
    val colours = listOf(
        Color(0xFF2E7D32), Color(0xFF7CB342), Color(0xFFF9A825),
        Color(0xFFE65100), Color(0xFFC62828), Color(0xFF6A1B9A),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..5).forEach { i ->
            val fill = if (eInk) ink else colours[i]
            Box(
                Modifier
                    .weight(1f)
                    .height(if (i == level) 14.dp else 10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .then(
                        if (i <= level) Modifier.background(fill)
                        else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(3.dp)),
                    ),
            )
        }
    }
}

/**
 * A static 24-bar chart. Strong bars are solid ink, the rest hollow — shape, not hue, so it
 * survives e-paper. [threshold] draws as a dashed reference line.
 */
@Composable
private fun HourBars(
    values: List<Float?>,
    max: Float,
    strong: (Int) -> Boolean,
    threshold: Float? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.outline
    Column {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val slot = size.width / 24f
            val barW = slot * 0.7f
            values.forEachIndexed { h, v ->
                if (v == null || v <= 0f) return@forEachIndexed
                val bh = (v / max).coerceIn(0.03f, 1f) * size.height
                val topLeft = Offset(h * slot + (slot - barW) / 2f, size.height - bh)
                if (strong(h)) {
                    drawRect(ink, topLeft, Size(barW, bh))
                } else {
                    drawRect(faint, topLeft, Size(barW, bh), style = Stroke(width = 1.dp.toPx()))
                }
            }
            threshold?.let { t ->
                val y = size.height - (t / max).coerceIn(0f, 1f) * size.height
                drawLine(
                    ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
            drawLine(ink, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18").forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The numbers behind the chart, one line per hour that has any: hour, chance (as a number and a
 * small filled gauge), and millimetres. Quiet stretches fold into a single "dry" line. Plain text
 * and static boxes only, so it reads the same on e-paper.
 */
@Composable
private fun RainTable(rows: List<Insights.RainRow>, fromHour: Int) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Hour by hour", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (fromHour > 0) Caption("From now on; earlier hours today are left out.")
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("Hour", style = MaterialTheme.typography.labelSmall, color = dim, modifier = Modifier.width(64.dp))
        Text("Chance", style = MaterialTheme.typography.labelSmall, color = dim, modifier = Modifier.weight(1f))
        Text(
            "Rain",
            style = MaterialTheme.typography.labelSmall,
            color = dim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp),
        )
    }
    HorizontalDivider()
    rows.forEach { r ->
        when (r) {
            is Insights.RainRow.Hour -> Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val likely = r.probPct >= 40
                val wet = r.mm >= 0.2
                Text(
                    hhmm(r.hour),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (likely || wet) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(64.dp),
                )
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(64.dp)
                            .height(10.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(r.probPct.coerceIn(0, 100) / 100f)
                                .background(ink),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${r.probPct}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (likely) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Text(
                    if (r.mm >= 0.05) mm(r.mm) else "–",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (wet) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
            is Insights.RainRow.Dry -> Text(
                "${hourSpan(r.startHour, r.endHour)} · dry",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
    Caption("Each hour runs to the next: 14:00 is rain between 14:00 and 15:00.")
}

private fun hhmm(hour: Int) = "%02d:00".format(hour)

private fun hourSpan(start: Int, end: Int) =
    "${hhmm(start)}–${if (end >= 24) "midnight" else hhmm(end)}"

private fun mm(v: Double) = String.format(Locale.US, if (v < 10) "%.1f mm" else "%.0f mm", v)
