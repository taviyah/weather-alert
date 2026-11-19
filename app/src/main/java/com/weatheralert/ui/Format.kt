package com.weatheralert.ui

import androidx.compose.ui.graphics.Color
import com.weatheralert.model.AdviceKind
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.AlertKind
import com.weatheralert.model.Severity
import com.weatheralert.model.TempUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val shortDay = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

fun LocalDate.relativeLabel(today: LocalDate): String = when (this) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> format(shortDay)
}

fun LocalDate.dateLabel(): String = format(shortDate)

/** Thresholds are stored in Celsius; only what reaches the screen is converted. */
fun Double.asTemp(unit: TempUnit): String {
    val v = if (unit == TempUnit.FAHRENHEIT) this * 9.0 / 5.0 + 32.0 else this
    return "${v.roundToInt()}°"
}

fun Double.asTempDelta(unit: TempUnit): String {
    val v = if (unit == TempUnit.FAHRENHEIT) this * 9.0 / 5.0 else this
    return String.format(Locale.US, "%.0f°", v)
}

fun TempUnit.suffix(): String = if (this == TempUnit.FAHRENHEIT) "°F" else "°C"

fun AlertKind.label(): String = when (this) {
    AlertKind.RAIN -> "Rain"
    AlertKind.DUST -> "Dust"
    AlertKind.AIR_QUALITY -> "Air quality"
    AlertKind.TEMP_DROP -> "Temp drop"
    AlertKind.TEMP_RISE -> "Temp rise"
    AlertKind.WIND_RISE -> "Wind up"
    AlertKind.WIND_DROP -> "Wind down"
    AlertKind.HEAT -> "Heat"
    AlertKind.COLD -> "Cold"
    AlertKind.FROST -> "Frost"
}

fun AlertKind.emoji(): String = when (this) {
    AlertKind.RAIN -> "🌧"
    AlertKind.DUST -> "🌪"
    AlertKind.AIR_QUALITY -> "😷"
    AlertKind.TEMP_DROP -> "🔽"
    AlertKind.TEMP_RISE -> "🔼"
    AlertKind.WIND_RISE -> "💨"
    AlertKind.WIND_DROP -> "🍃"
    AlertKind.HEAT -> "🔥"
    AlertKind.COLD -> "🧊"
    AlertKind.FROST -> "❄"
}

fun severityColor(severity: Severity): Color =
    if (severity == Severity.MAJOR) Color(0xFFC62828) else Color(0xFFE08700)

fun AdviceKind.emoji(): String = when (this) {
    AdviceKind.LAUNDRY -> "🧺"
    AdviceKind.MASK -> "😷"
    AdviceKind.CLOTHING -> "🧥"
}

fun AdviceKind.label(): String = when (this) {
    AdviceKind.LAUNDRY -> "Laundry"
    AdviceKind.MASK -> "Mask"
    AdviceKind.CLOTHING -> "Clothing"
}

fun adviceColor(level: AdviceLevel): Color = when (level) {
    AdviceLevel.GOOD -> Color(0xFF2E7D32)
    AdviceLevel.CAUTION -> Color(0xFFE08700)
    AdviceLevel.BAD -> Color(0xFFC62828)
}

/** WMO weather interpretation codes, in words. */
fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    in 71..77 -> "Snow"
    80 -> "Light showers"
    81 -> "Showers"
    82 -> "Violent showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorms"
    96, 99 -> "Thunderstorms with hail"
    else -> "Cloudy"
}

/** WMO weather interpretation codes, condensed to what fits on a forecast strip. */
fun weatherEmoji(code: Int): String = when (code) {
    0 -> "☀"
    1, 2 -> "🌤"
    3 -> "☁"
    45, 48 -> "🌫"
    in 51..57 -> "🌦"
    in 61..67 -> "🌧"
    in 71..77 -> "🌨"
    in 80..82 -> "🌦"
    in 85..86 -> "🌨"
    in 95..99 -> "⛈"
    else -> "☁"
}
