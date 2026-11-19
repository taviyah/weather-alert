package com.weatheralert.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.weatheralert.MainActivity
import com.weatheralert.R
import com.weatheralert.model.AdviceKind
import com.weatheralert.model.DayAdvice
import com.weatheralert.model.Severity
import com.weatheralert.model.WatchedLocation
import com.weatheralert.model.WeatherAlert
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What one check found for one place, still to be told to the user. */
class LocationNews(
    val location: WatchedLocation,
    val alerts: List<WeatherAlert>,
    val advice: List<DayAdvice>,
)

object Notifier {

    const val CHANNEL_MAJOR = "alerts_major"
    const val CHANNEL_NOTABLE = "alerts_notable"

    /** The one alert notification. 2 is taken by the keep-alive service. */
    private const val ALERTS_ID = 1

    /** Versions up to 0.1 posted one notification per place in this group. */
    private const val LEGACY_GROUP = "com.weatheralert.ALERTS"

    private const val MAX_LINES = 6

    // The lines on screen are stored inside the notification itself, so a later check can add to
    // them instead of wiping what the user has not read yet. Dismissing it clears them for free.
    private const val KEY_DAYS = "com.weatheralert.days"
    private const val KEY_PLACES = "com.weatheralert.places"
    private const val KEY_TEXTS = "com.weatheralert.texts"
    private const val KEY_FLAGS = "com.weatheralert.flags"
    private const val FLAG_ADVICE = 1
    private const val FLAG_MAJOR = 2

    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    private data class Line(val epochDay: Long, val place: String, val text: String, val flags: Int) {
        val advice get() = flags and FLAG_ADVICE != 0
        val major get() = flags and FLAG_MAJOR != 0
    }

    fun createChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        // Two channels rather than one so the user can silence the routine "rain on Thursday"
        // stream in system settings while a sharp temperature drop still makes a sound.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAJOR,
                "Major alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Heavy rain, heavy dust, sharp temperature swings, frost" },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NOTABLE,
                "Notable alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Rain, dusty air, poor air quality, moderate temperature changes" },
        )
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One notification for everything, not one per alert or per place. A single incoming front
     * can trip rain, wind-blown dust and a temperature drop across three days and two places at
     * once; a stack of notifications for one weather system would train the user to swipe them
     * away. Anything still on screen from an earlier check is kept and merged in.
     */
    fun notify(context: Context, news: List<LocationNews>) {
        val fresh = news.flatMap { n ->
            val place = n.location.name
            // Advice leads. "Do not hang laundry" is what the user acts on; "PM10 140" is the
            // evidence for it, and belongs after.
            n.advice.map { a ->
                Line(
                    a.localDate.toEpochDay(), place,
                    "${adviceIcon(a.kind)} ${a.headline} — ${a.reason}",
                    FLAG_ADVICE or FLAG_MAJOR,
                )
            } + n.alerts.map { a ->
                Line(
                    a.localDate.toEpochDay(), place,
                    "${a.headline} — ${a.detail}",
                    if (a.severity == Severity.MAJOR) FLAG_MAJOR else 0,
                )
            }
        }
        if (fresh.isEmpty() || !canPost(context)) return

        val today = LocalDate.now()
        val mgr = context.getSystemService(NotificationManager::class.java)
        val active = runCatching { mgr?.activeNotifications.orEmpty() }.getOrDefault(emptyArray())
        active.filter { it.notification.group == LEGACY_GROUP }.forEach { mgr?.cancel(it.id) }
        val shown = active.firstOrNull { it.id == ALERTS_ID }?.notification?.extras
            ?.let(::readLines).orEmpty()

        // Days already past drop off; the same line from an earlier check is not repeated.
        val lines = (fresh + shown)
            .filter { it.epochDay >= today.toEpochDay() }
            .distinctBy { Triple(it.epochDay, it.place, it.text) }
            .sortedWith(compareBy<Line> { it.epochDay }.thenBy { !it.advice }.thenBy { !it.major })
        val places = lines.map { it.place }.distinct()

        fun whenText(epochDay: Long) = when (val date = LocalDate.ofEpochDay(epochDay)) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> date.format(dayFormat)
        }
        // The place is only worth repeating on every line when there is more than one.
        val rendered = lines.map { l ->
            buildString {
                append(whenText(l.epochDay)).append(" · ")
                if (places.size > 1) append(l.place).append(" · ")
                append(l.text)
            }
        }

        val title = buildString {
            if (lines.any { it.major }) append("⚠ ")
            append(places.joinToString(", "))
        }

        // Straight to the home screen: it shows every place and day at once, which is the
        // overview a merged notification is pointing at.
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        rendered.take(MAX_LINES).forEach { style.addLine(it) }
        if (rendered.size > MAX_LINES) style.setSummaryText("+${rendered.size - MAX_LINES} more")

        // The channel follows what is new, so a fresh Notable line does not re-ring with the
        // Major sound of something the user has already been told about.
        val major = fresh.any { it.major }
        val notification = NotificationCompat.Builder(
            context,
            if (major) CHANNEL_MAJOR else CHANNEL_NOTABLE,
        )
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(rendered.first())
            .setStyle(style)
            .setNumber(lines.size)
            .setPriority(if (major) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .addExtras(writeLines(lines))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ALERTS_ID, notification) }
    }

    private fun adviceIcon(kind: AdviceKind) = when (kind) {
        AdviceKind.LAUNDRY -> "🧺"
        AdviceKind.MASK -> "😷"
        AdviceKind.CLOTHING -> "🧥"
    }

    private fun writeLines(lines: List<Line>) = Bundle().apply {
        putLongArray(KEY_DAYS, lines.map { it.epochDay }.toLongArray())
        putStringArray(KEY_PLACES, lines.map { it.place }.toTypedArray())
        putStringArray(KEY_TEXTS, lines.map { it.text }.toTypedArray())
        putIntArray(KEY_FLAGS, lines.map { it.flags }.toIntArray())
    }

    private fun readLines(extras: Bundle): List<Line> {
        val days = extras.getLongArray(KEY_DAYS) ?: return emptyList()
        val places = extras.getStringArray(KEY_PLACES) ?: return emptyList()
        val texts = extras.getStringArray(KEY_TEXTS) ?: return emptyList()
        val flags = extras.getIntArray(KEY_FLAGS) ?: return emptyList()
        return days.indices
            .filter { it < places.size && it < texts.size && it < flags.size }
            .map { Line(days[it], places[it], texts[it], flags[it]) }
    }
}
