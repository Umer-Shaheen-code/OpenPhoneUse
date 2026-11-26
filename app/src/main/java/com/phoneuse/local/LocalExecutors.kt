package com.phoneuse.local

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.personal.agent.core.Apps
import com.personal.agent.core.Calc
import com.personal.agent.core.ContactCandidate
import com.personal.agent.core.ContactMatcher
import com.personal.agent.core.ContactSource
import com.personal.agent.core.Conversation
import com.personal.agent.core.Dates
import com.personal.agent.core.DigestSpeaker
import com.personal.agent.core.MediaMath
import com.personal.agent.core.MessageText
import com.personal.agent.core.ParsedCommand
import com.personal.agent.core.Risk
import com.personal.agent.core.RiskPolicy
import com.personal.agent.core.SlotValue
import com.personal.agent.core.TimeSpeech
import com.phoneuse.agent.UserGate
import com.phoneuse.app.ConfirmMode
import com.phoneuse.phone.AndroidCalendarSource
import com.phoneuse.phone.AndroidLocationSource
import com.phoneuse.phone.Declared
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.risk.RiskCategory
import com.phoneuse.routine.AlarmReminderScheduler
import com.phoneuse.service.PhoneUseAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** Result of trying to handle a turn locally without an LLM. */
sealed interface LocalExecResult {
    data class Done(
        val reply: String,
        val steps: List<String> = emptyList(),
        val launchIntent: Intent? = null,
        val stayInApp: Boolean = false,
        val listenAgain: Boolean = false,
        val afterClose: (suspend () -> Unit)? = null,
    ) : LocalExecResult

    /** Pre-execution miss (e.g., unknown app, missing reply handle, complex query): seamlessly hand off to AI. */
    data class FallbackToAi(val reason: String) : LocalExecResult
}

internal data class LocalTurnContext(
    val dialog: LocalDialogState,
    val gate: UserGate,
    val confirmMode: ConfirmMode,
    val hasAiBackend: Boolean,
    val use24h: Boolean,
)

internal class LocalExecutors(
    private val context: Context,
    private val store: LocalAgentStore,
    private val notifications: LiveNotificationHub,
    private val media: MediaHub,
) {
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private var torchOn = false
    private val torchCallback by lazy {
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) { torchOn = enabled }
        }
        runCatching {
            context.getSystemService(CameraManager::class.java)?.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        }
        cb
    }

    suspend fun execute(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult = when (cmd.intent) {
        // ---- Information ----
        "time.now" -> LocalExecResult.Done("It's ${TimeSpeech.time(LocalTime.now(), tx.use24h)}.")
        "time.in_city" -> timeIn(cmd, tx)
        "date.today" -> LocalExecResult.Done("Today is ${TimeSpeech.date(LocalDate.now())}.")
        "day.today" -> dayToday(cmd)
        "date.days_until" -> daysUntil(cmd)
        "battery.status" -> battery()
        "calc" -> calc(cmd)
        "weather" -> weather(cmd)
        "calendar.agenda" -> agenda(cmd, tx)

        // ---- Device ----
        "volume.set" -> volumeSet(cmd)
        "volume.up" -> volumeStep(+1, cmd)
        "volume.down" -> volumeStep(-1, cmd)
        "volume.mute" -> mute(true)
        "volume.unmute" -> mute(false)
        "ringer.mode" -> ringer(cmd, tx)
        "dnd.set" -> dnd(cmd, tx)
        "torch.set" -> torch(cmd)
        "brightness.set" -> brightnessSet(cmd, tx)
        "brightness.up" -> brightnessStep(+1, tx)
        "brightness.down" -> brightnessStep(-1, tx)
        "alarm.set" -> alarm(cmd, tx)
        "timer.set" -> timer(cmd)
        "alarm.dismiss" -> LocalExecResult.Done(
            "Dismissing alarm.",
            launchIntent = Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        "alarm.snooze" -> LocalExecResult.Done(
            "Snoozing alarm.",
            launchIntent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
                durationMs(cmd)?.let { putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, (it / 60_000).toInt().coerceAtLeast(1)) }
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        "reminder.set" -> reminder(cmd, tx)
        "note.add" -> noteAdd(cmd)
        "note.read" -> noteRead()
        "list.add" -> listAdd(cmd)
        "list.read" -> listRead(cmd)
        "app.open" -> openApp(cmd)
        "nav.go" -> navigate(cmd)
        "device.lock" -> globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Screen locked.")
        "device.screenshot" -> globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Took a screenshot.")
        "device.home" -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "Went to home screen.")
        "device.back" -> globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "Went back.")

        // ---- Media ----
        "media.seek_relative" -> seekRelative(cmd)
        "media.seek_absolute" -> seekAbsolute(cmd)
        "media.pause" -> mediaControl(cmd, KeyEvent.KEYCODE_MEDIA_PAUSE) { media.pause(it)?.let { a -> "Paused $a." } }
        "media.play" -> mediaControl(cmd, KeyEvent.KEYCODE_MEDIA_PLAY) { media.play(it)?.let { a -> "Playing $a." } }
        "media.next" -> mediaControl(cmd, KeyEvent.KEYCODE_MEDIA_NEXT) { media.next(it)?.let { "Skipped to next." } }
        "media.previous" -> mediaControl(cmd, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { media.previous(it)?.let { "Went to previous." } }
        "media.now_playing" -> nowPlaying(cmd)
        "media.speed" -> speed(cmd)
        "media.like" -> like(cmd)
        "media.play_search" -> playSearch(cmd)

        // ---- Notifications & Messaging ----
        "notif.read" -> readNotifications(cmd, tx)
        "list.more" -> moreNotifications(tx)
        "msg.send" -> sendMessage(cmd, tx)
        "notif.mark_read" -> markRead(cmd, tx)
        "notif.clear" -> clearNotifications(cmd)
        "notif.archive" -> archiveEmail(cmd, tx)

        // ---- Optional Permission-Gated Phone/Email ----
        "call.contact" -> callContact(cmd)
        "call.missed", "email.read_full", "email.reply" ->
            LocalExecResult.FallbackToAi("handled by OpenPhoneUse AI / connectors")

        "meta.help" -> LocalExecResult.Done(
            "For fast everyday tasks without AI, I can tell the time and date, do math, check battery and weather, " +
                "control volume, flashlight, brightness, DND, alarms, timers, reminders, notes, lists, and media playback, " +
                "and read or reply to your notifications. For anything complex, multi-step, or on-screen, I automatically use AI.",
        )

        else -> LocalExecResult.FallbackToAi("unhandled local intent ${cmd.intent}")
    }

    // ------------------------------------------------------------------ Info
    private fun timeIn(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        val place = cmd.text("city") ?: return LocalExecResult.FallbackToAi("missing city")
        val zone = Zones.find(place) ?: return LocalExecResult.FallbackToAi("unknown timezone for $place")
        val t = ZonedDateTime.now(zone)
        val local = ZonedDateTime.now()
        val diffH = (t.offset.totalSeconds - local.offset.totalSeconds) / 3600.0
        val rel = when {
            diffH == 0.0 -> "same as here"
            else -> {
                val whole = kotlin.math.abs(diffH).toInt()
                val half = if (kotlin.math.abs(diffH) % 1.0 != 0.0) " and a half" else ""
                "${MediaMath.plural(whole, "hour")}$half ${if (diffH > 0) "ahead" else "behind"}"
            }
        }
        val day = when (t.toLocalDate().compareTo(local.toLocalDate())) {
            1 -> " tomorrow"
            -1 -> " yesterday"
            else -> ""
        }
        val label = place.replaceFirstChar { it.uppercase() }
        return LocalExecResult.Done("In $label it's ${TimeSpeech.time(t.toLocalTime(), tx.use24h)}$day, $rel.")
    }

    private fun dayToday(cmd: ParsedCommand): LocalExecResult {
        val today = LocalDate.now().dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val asked = (cmd.slots["day"] as? SlotValue.Enum)?.value
        val msg = if (asked != null) {
            if (asked.equals(today, ignoreCase = true)) "Yes, it's $today." else "No, it's $today."
        } else {
            "It's $today."
        }
        return LocalExecResult.Done(msg)
    }

    private fun daysUntil(cmd: ParsedCommand): LocalExecResult {
        val spec = (cmd.slots["date"] as? SlotValue.Date)?.date ?: return LocalExecResult.FallbackToAi("missing date")
        val today = LocalDate.now()
        val d = Dates.resolve(spec, today)
        val n = ChronoUnit.DAYS.between(today, d)
        val msg = when (n) {
            0L -> "That's today."
            1L -> "That's tomorrow."
            else -> "$n days, until ${TimeSpeech.date(d, withYear = d.year != today.year)}."
        }
        return LocalExecResult.Done(msg)
    }

    private fun calc(cmd: ParsedCommand): LocalExecResult {
        val r = (cmd.slots["expr"] as? SlotValue.Expr)?.result ?: return LocalExecResult.FallbackToAi("calc parse miss")
        return LocalExecResult.Done("${r.spoken}.")
    }

    private suspend fun weather(cmd: ParsedCommand): LocalExecResult {
        val locSource = AndroidLocationSource(context)
        if (!locSource.hasPermission() || !locSource.isEnabled()) {
            return LocalExecResult.FallbackToAi("location unavailable locally")
        }
        val fix = locSource.current() ?: return LocalExecResult.FallbackToAi("no location fix")
        val whenWord = (cmd.slots["when"] as? SlotValue.Enum)?.value
            ?: if (cmd.transcript.contains("tomorrow", ignoreCase = true)) "tomorrow" else "today"
        return withContext(Dispatchers.IO) {
            runCatching {
                val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2"
                    .format(Locale.US, fix.lat, fix.lng)
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4_000
                    readTimeout = 4_000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(body)
                val daily = j.getJSONObject("daily")
                val i = if (whenWord == "tomorrow") 1 else 0
                val max = daily.getJSONArray("temperature_2m_max").getDouble(i).toInt()
                val min = daily.getJSONArray("temperature_2m_min").getDouble(i).toInt()
                val rain = daily.getJSONArray("precipitation_probability_max").optInt(i, 0)
                val code = daily.getJSONArray("weather_code").getInt(i)
                val s = if (i == 0) {
                    val cur = j.getJSONObject("current")
                    "It's ${cur.getDouble("temperature_2m").toInt()}°C and ${describeWeather(cur.getInt("weather_code"))}. Today: high $max°, low $min°" +
                        (if (rain >= 20) ", $rain% chance of rain." else ".")
                } else {
                    "Tomorrow: ${describeWeather(code)}, high $max°, low $min°" + (if (rain >= 20) ", $rain% chance of rain." else ".")
                }
                LocalExecResult.Done(s)
            }.getOrElse { LocalExecResult.FallbackToAi("weather lookup failed") }
        }
    }

    private fun describeWeather(code: Int) = when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "foggy"
        in 51..57 -> "drizzly"
        in 61..67 -> "rainy"
        in 71..77 -> "snowy"
        in 80..82 -> "showery"
        in 95..99 -> "stormy"
        else -> "mixed"
    }

    private fun agenda(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        val cal = AndroidCalendarSource(context)
        if (!cal.canRead()) return LocalExecResult.FallbackToAi("calendar permission missing")
        val tomorrow = (cmd.slots["day"] as? SlotValue.Enum)?.value == "tomorrow"
        val day = LocalDate.now().plusDays(if (tomorrow) 1 else 0)
        val now = LocalDateTime.now()
        val from = if (tomorrow) day.atStartOfDay() else maxOf(day.atStartOfDay(), now.minusMinutes(30))
        val to = day.plusDays(1).atStartOfDay()
        val events = cal.events(from, to).take(6).map { ev ->
            if (ev.allDay) "${ev.title} (all day)"
            else "${ev.title} at ${TimeSpeech.time(ev.start.toLocalTime(), tx.use24h)}"
        }
        val label = if (tomorrow) "tomorrow" else "today"
        val reply = if (events.isEmpty()) {
            "Nothing ${if (tomorrow) "" else "else "}on your calendar $label."
        } else {
            "${MediaMath.plural(events.size, "event")} $label: ${DigestSpeaker.joinAnd(events)}."
        }
        return LocalExecResult.Done(reply)
    }

    // ------------------------------------------------------------------ Device
    private fun battery(): LocalExecResult {
        val bm = context.getSystemService(BatteryManager::class.java)
            ?: return LocalExecResult.FallbackToAi("no battery manager")
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val toFull = bm.computeChargeTimeRemaining()
        val s = buildString {
            append("Battery is at $pct%")
            if (charging) {
                append(" and charging")
                if (toFull > 60_000) append(" (full in about ${MediaMath.spokenDuration((toFull / 60_000) * 60_000)})")
            }
            append(".")
        }
        return LocalExecResult.Done(s)
    }

    private fun volumeSet(cmd: ParsedCommand): LocalExecResult {
        val pct = (cmd.slots["level"] as? SlotValue.Percent)?.value ?: return LocalExecResult.Done("What volume percentage?", listenAgain = true)
        val a = audio ?: return LocalExecResult.FallbackToAi("no audio manager")
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * pct.coerceIn(0, 100) / 100.0).roundToInt()
        a.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return LocalExecResult.Done("Media volume set to ${pct.coerceIn(0, 100)}%.")
    }

    private fun volumeStep(dir: Int, cmd: ParsedCommand): LocalExecResult {
        val a = audio ?: return LocalExecResult.FallbackToAi("no audio manager")
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val stepPct = (cmd.slots["level"] as? SlotValue.Percent)?.value
        val cur = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = if (stepPct != null) (max * stepPct / 100.0).roundToInt().coerceAtLeast(1) else maxOf(1, max / 7)
        val next = (cur + dir * step).coerceIn(0, max)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
        val pct = if (max > 0) (next * 100.0 / max).roundToInt() else 0
        return LocalExecResult.Done("Media volume $pct%.")
    }

    private fun mute(on: Boolean): LocalExecResult {
        val a = audio ?: return LocalExecResult.FallbackToAi("no audio manager")
        a.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return LocalExecResult.Done(if (on) "Muted media." else "Unmuted media.")
    }

    private fun ringer(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        val a = audio ?: return LocalExecResult.FallbackToAi("no audio manager")
        val wantOff = cmd.transcript.contains(" off", ignoreCase = true)
        val mode = if (wantOff) {
            AudioManager.RINGER_MODE_NORMAL
        } else {
            when ((cmd.slots["mode"] as? SlotValue.Enum)?.value) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate", "vibration" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        }
        if (mode == AudioManager.RINGER_MODE_SILENT && nm?.isNotificationPolicyAccessGranted == false) {
            return if (tx.hasAiBackend) {
                LocalExecResult.FallbackToAi("no notification policy access for silent mode")
            } else {
                LocalExecResult.Done(
                    "Do Not Disturb access is needed for silent mode. Opening settings.",
                    launchIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    stayInApp = true,
                )
            }
        }
        return runCatching {
            a.ringerMode = mode
            LocalExecResult.Done(
                when (a.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "Phone is on silent."
                    AudioManager.RINGER_MODE_VIBRATE -> "Vibrate mode on."
                    else -> "Ringer is on."
                },
            )
        }.getOrElse { LocalExecResult.FallbackToAi("ringer mode change failed") }
    }

    private fun dnd(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return LocalExecResult.FallbackToAi("no notification manager")
        if (!nm.isNotificationPolicyAccessGranted) {
            return if (tx.hasAiBackend) {
                LocalExecResult.FallbackToAi("no DND access; AI can toggle via Quick Settings")
            } else {
                LocalExecResult.Done(
                    "Do Not Disturb access is needed. Opening settings.",
                    launchIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    stayInApp = true,
                )
            }
        }
        val on = (cmd.slots["state"] as? SlotValue.Enum)?.value == "on"
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return LocalExecResult.Done(if (on) "Do Not Disturb is on." else "Do Not Disturb is off.")
    }

    private fun torch(cmd: ParsedCommand): LocalExecResult {
        torchCallback
        val cm = context.getSystemService(CameraManager::class.java) ?: return LocalExecResult.FallbackToAi("no camera manager")
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return LocalExecResult.Done("This phone has no flashlight.")
        val on = when ((cmd.slots["state"] as? SlotValue.Enum)?.value) {
            "on" -> true
            "off" -> false
            else -> !torchOn
        }
        return runCatching {
            cm.setTorchMode(id, on)
            torchOn = on
            LocalExecResult.Done(if (on) "Flashlight on." else "Flashlight off.")
        }.getOrElse { LocalExecResult.Done("Couldn't change flashlight because the camera is busy.") }
    }

    private fun brightnessSet(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        if (!Settings.System.canWrite(context)) {
            return if (tx.hasAiBackend) {
                LocalExecResult.FallbackToAi("no WRITE_SETTINGS; AI can adjust via Quick Settings")
            } else {
                LocalExecResult.Done(
                    "Allow 'Modify system settings' to change brightness directly.",
                    launchIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    stayInApp = true,
                )
            }
        }
        val pct = (cmd.slots["level"] as? SlotValue.Percent)?.value ?: return LocalExecResult.Done("What brightness percentage?", listenAgain = true)
        val value = (pct.coerceIn(0, 100) * 255 / 100).coerceIn(1, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return LocalExecResult.Done("Brightness set to ${pct.coerceIn(0, 100)}%.")
    }

    private fun brightnessStep(dir: Int, tx: LocalTurnContext): LocalExecResult {
        if (!Settings.System.canWrite(context)) {
            return if (tx.hasAiBackend) {
                LocalExecResult.FallbackToAi("no WRITE_SETTINGS; AI can adjust via Quick Settings")
            } else {
                LocalExecResult.Done(
                    "Allow 'Modify system settings' to change brightness directly.",
                    launchIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    stayInApp = true,
                )
            }
        }
        val cur = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        val next = (cur + dir * 51).coerceIn(1, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        val pct = (next * 100.0 / 255.0).roundToInt()
        return LocalExecResult.Done("Brightness set to $pct%.")
    }

    private fun alarm(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        val tod = (cmd.slots["time"] as? SlotValue.Time)?.tod
        val at = if (tod != null) {
            TimeSpeech.nextOccurrence(LocalDateTime.now(), tod)
        } else {
            durationMs(cmd)?.let { LocalDateTime.now().plusSeconds(it / 1000) }
                ?: return LocalExecResult.Done("For what time?", listenAgain = true)
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "OpenPhoneUse")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) return LocalExecResult.FallbackToAi("no clock app")
        val tomorrow = at.toLocalDate() != LocalDate.now()
        return LocalExecResult.Done(
            "Alarm set for ${TimeSpeech.time(at.toLocalTime(), tx.use24h)}${if (tomorrow) " tomorrow" else ""}.",
            launchIntent = i,
        )
    }

    private fun timer(cmd: ParsedCommand): LocalExecResult {
        val ms = durationMs(cmd) ?: return LocalExecResult.Done("For how long?", listenAgain = true)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, (ms / 1000).toInt().coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "OpenPhoneUse")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) return LocalExecResult.FallbackToAi("no clock app")
        return LocalExecResult.Done("Timer set for ${MediaMath.spokenDuration(ms)}.", launchIntent = i)
    }

    private fun reminder(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        val text = cmd.text("text") ?: return LocalExecResult.Done("What should I remind you about?", listenAgain = true)
        val tod = (cmd.slots["time"] as? SlotValue.Time)?.tod
        val dur = durationMs(cmd)
        val atMillis = dur?.let { System.currentTimeMillis() + it }
            ?: tod?.let { TimeSpeech.nextOccurrence(LocalDateTime.now(), it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        if (atMillis == null) {
            tx.dialog.slotRequest = LocalSlotRequest("when") { said ->
                val p = com.personal.agent.core.CommandParser.parse("remind me $said to $text")
                    .firstOrNull { it.intent == "reminder.set" }
                if (p != null) reminder(p, tx) else LocalExecResult.Done("Sorry, I didn't catch the time.")
            }
            return LocalExecResult.Done("When should I remind you?", listenAgain = true)
        }
        val scheduler = AlarmReminderScheduler(context)
        scheduler.oneShot(Instant.ofEpochMilli(atMillis), text)
        val whenSpoken = dur?.let { "in ${MediaMath.spokenDuration(it)}" }
            ?: "at ${TimeSpeech.time(LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault()).toLocalTime(), tx.use24h)}"
        return LocalExecResult.Done("Okay, I'll remind you $whenSpoken: $text.")
    }

    private fun noteAdd(cmd: ParsedCommand): LocalExecResult {
        val n = cmd.text("note") ?: return LocalExecResult.Done("What should I note?", listenAgain = true)
        store.addNote(n)
        return LocalExecResult.Done("Noted: $n.")
    }

    private fun noteRead(): LocalExecResult {
        val notes = store.notes().takeLast(5).reversed()
        if (notes.isEmpty()) return LocalExecResult.Done("You don't have any notes.")
        val reply = if (notes.size == 1) "Your note: ${notes[0].text}." else "Your latest notes: " + notes.joinToString(". ") { it.text } + "."
        return LocalExecResult.Done(reply)
    }

    private fun listAdd(cmd: ParsedCommand): LocalExecResult {
        val item = cmd.text("item") ?: return LocalExecResult.Done("Add what?", listenAgain = true)
        val list = normalizeList(cmd.text("list") ?: "shopping")
        store.addToList(list, item)
        return LocalExecResult.Done("Added $item to your $list list.")
    }

    private fun listRead(cmd: ParsedCommand): LocalExecResult {
        val list = normalizeList(cmd.text("list") ?: "shopping")
        val items = store.list(list)
        val reply = if (items.isEmpty()) "Your $list list is empty." else "Your $list list: ${DigestSpeaker.joinAnd(items)}."
        return LocalExecResult.Done(reply)
    }

    private fun normalizeList(s: String) = s.lowercase()
        .removeSuffix(" list")
        .removePrefix("my ")
        .removePrefix("the ")
        .let { if (it == "grocery" || it == "groceries") "shopping" else it }

    private fun openApp(cmd: ParsedCommand): LocalExecResult {
        val name = cmd.text("appname") ?: return LocalExecResult.FallbackToAi("missing app name")
        val pm = context.packageManager
        val launchables = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val candidates = launchables.map {
            ContactCandidate(it.activityInfo.packageName, it.loadLabel(pm).toString(), ContactSource.CONTACTS)
        }
        val matcher = ContactMatcher(candidates)
        val direct = matcher.rank(name).firstOrNull()?.takeIf { it.nameSim >= 0.85 }
        val resolved = direct ?: APP_SYNONYMS[name.trim().lowercase()]?.firstNotNullOfOrNull { alt ->
            matcher.rank(alt).firstOrNull()?.takeIf { it.nameSim >= 0.85 }
        }
        if (resolved != null) {
            val intent = pm.getLaunchIntentForPackage(resolved.candidate.id)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) {
                return LocalExecResult.Done("Opening ${resolved.candidate.displayName}.", launchIntent = intent, stayInApp = true)
            }
        }
        val fallbackIntent = systemAppFallbackIntent(name.trim().lowercase())
        if (fallbackIntent != null && fallbackIntent.resolveActivity(pm) != null) {
            return LocalExecResult.Done("Opening ${name.trim()}.", launchIntent = fallbackIntent, stayInApp = true)
        }
        return LocalExecResult.FallbackToAi("app '$name' not matched locally")
    }

    private fun systemAppFallbackIntent(key: String): Intent? = when (key) {
        "call", "dialer", "phone" -> Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        "browser", "internet" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        "messages", "sms", "texts" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        "gallery", "photos" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        "email", "mail" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        "clock", "alarm", "alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        "camera" -> Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else -> null
    }

    private fun navigate(cmd: ParsedCommand): LocalExecResult {
        val place = cmd.text("place") ?: return LocalExecResult.FallbackToAi("missing destination")
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = if (nav.resolveActivity(context.packageManager) != null) {
            nav
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return LocalExecResult.Done("Navigating to $place.", launchIntent = launch, stayInApp = true)
    }

    private suspend fun globalAction(action: Int, speech: String): LocalExecResult {
        val svc = PhoneUseAccessibilityService.instance
            ?: return LocalExecResult.FallbackToAi("accessibility service unavailable")
        return LocalExecResult.Done(
            speech,
            stayInApp = true,
            afterClose = {
                delay(400)
                svc.performGlobalAction(action)
            },
        )
    }

    // ------------------------------------------------------------------ Media
    private suspend fun seekRelative(cmd: ParsedCommand): LocalExecResult {
        val d = durationMs(cmd) ?: return LocalExecResult.Done("How far should I skip?", listenAgain = true)
        val sign = if ((cmd.slots["direction"] as? SlotValue.Enum)?.value == "back") -1 else 1
        return seekResult(media.seek(appSlot(cmd), sign * d, null), "${if (sign > 0) "Forward" else "Back"} ${MediaMath.spokenDuration(d)}")
    }

    private suspend fun seekAbsolute(cmd: ParsedCommand): LocalExecResult {
        val pos = (cmd.slots["position"] as? SlotValue.Position)?.ms
            ?: return LocalExecResult.Done("Where should I jump to?", listenAgain = true)
        return seekResult(media.seek(appSlot(cmd), null, pos), if (pos == 0L) "Restarted" else "Jumped to ${MediaMath.clock(pos)}")
    }

    private fun seekResult(r: MediaHub.SeekResult, label: String): LocalExecResult = when (r) {
        MediaHub.SeekResult.NoSession -> LocalExecResult.Done("Nothing is playing right now.")
        is MediaHub.SeekResult.NotSupported -> LocalExecResult.Done("${r.app} doesn't allow seeking right now.")
        is MediaHub.SeekResult.Done -> when {
            r.clampedEnd -> LocalExecResult.Done("Skipped to the end of ${r.app}.")
            !r.verified -> LocalExecResult.Done("Asked ${r.app} to move to ${MediaMath.clock(r.target)}.")
            else -> LocalExecResult.Done("$label on ${r.app}.")
        }
    }

    private fun mediaControl(cmd: ParsedCommand, keyCode: Int, op: (String?) -> String?): LocalExecResult {
        op(appSlot(cmd))?.let { return LocalExecResult.Done(it) }
        val a = audio
        if (!notifications.isAccessEnabled() && a != null) {
            val now = android.os.SystemClock.uptimeMillis()
            a.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            a.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            val label = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused."
                KeyEvent.KEYCODE_MEDIA_PLAY -> "Playing."
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Skipped to next."
                else -> "Went to previous."
            }
            return LocalExecResult.Done(label)
        }
        return LocalExecResult.Done("Nothing is playing right now.")
    }

    private fun nowPlaying(cmd: ParsedCommand): LocalExecResult {
        val np = media.nowPlaying(appSlot(cmd)) ?: return LocalExecResult.Done("Nothing is playing right now.")
        if (np.title == null) {
            return LocalExecResult.Done("${np.app} is ${if (np.playing) "playing" else "paused"}, without track metadata.")
        }
        val by = np.artist?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""
        val where = np.durationMs?.let { ", ${MediaMath.clock(np.positionMs)} of ${MediaMath.clock(it)}" } ?: ""
        return LocalExecResult.Done("${if (np.playing) "Playing" else "Paused"}: ${np.title}$by on ${np.app}$where.")
    }

    private fun speed(cmd: ParsedCommand): LocalExecResult {
        val s = (cmd.slots["speed"] as? SlotValue.Speed)?.value ?: return LocalExecResult.Done("What playback speed?", listenAgain = true)
        val (app, ok) = media.setSpeed(appSlot(cmd), s) ?: return LocalExecResult.Done("Nothing is playing right now.")
        return LocalExecResult.Done(if (ok) "Set $app speed to ${s}x." else "$app doesn't let me change the speed.")
    }

    private fun like(cmd: ParsedCommand): LocalExecResult {
        val (app, action) = media.like(appSlot(cmd)) ?: return LocalExecResult.Done("Nothing is playing right now.")
        return LocalExecResult.Done(if (action != null) "Liked on $app." else "$app doesn't offer a like action.")
    }

    private fun playSearch(cmd: ParsedCommand): LocalExecResult {
        val q = cmd.text("query") ?: return LocalExecResult.FallbackToAi("missing query")
        val app = appSlot(cmd)
        media.playFromSearch(app ?: media.target()?.packageName?.let { Apps.idForPackage(it) }, q)?.let {
            return LocalExecResult.Done("Playing $q on $it.", stayInApp = true)
        }
        val intent = when (app) {
            "youtube" -> Intent(Intent.ACTION_SEARCH).setPackage(Apps.packages.getValue("youtube").first())
                .putExtra(android.app.SearchManager.QUERY, q)
            null -> Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(android.app.SearchManager.QUERY, q)
            else -> Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(Apps.packages[app]?.firstOrNull())
                .putExtra(android.app.SearchManager.QUERY, q)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return LocalExecResult.FallbackToAi("no local media search handler")
        }
        return LocalExecResult.Done(
            "Playing $q${app?.let { " on " + Apps.displayName(it) } ?: ""}.",
            launchIntent = intent,
            stayInApp = true,
        )
    }

    // ------------------------------------------------------------------ Notifications & Messaging
    private fun candidates(convs: List<Conversation>): List<ContactCandidate> {
        val now = System.currentTimeMillis()
        val aliases = store.aliases()
        val out = ArrayList<ContactCandidate>()
        for (c in convs) {
            val recency = ((1.0 - ((now - c.updatedAt).coerceAtLeast(0L) / (6.0 * 3_600_000.0))).coerceIn(0.0, 1.0)) * 0.12
            if (c.app == "gmail") {
                out += ContactCandidate("conv:${c.key}", c.title, if (c.live) ContactSource.LIVE_CONVERSATION else ContactSource.RECENT_CONVERSATION, app = "gmail", recencyBoost = recency)
                continue
            }
            val last = c.messages.lastOrNull { !it.fromMe }
            val source = if (c.live && c.unread.isNotEmpty()) ContactSource.LIVE_CONVERSATION else ContactSource.RECENT_CONVERSATION
            out += ContactCandidate(
                "conv:${c.key}", c.title, source,
                aliases = aliases.filterValues { it == c.title }.keys.toList(),
                app = c.app, isGroup = c.isGroup, lastMessage = last?.text, recencyBoost = recency,
            )
            if (c.isGroup) {
                c.messages.mapNotNull { it.sender }.filter { it != "You" }.distinct().forEach { s ->
                    out += ContactCandidate(
                        "conv:${c.key}", s, ContactSource.GROUP_SENDER,
                        app = c.app, isGroup = true, groupName = c.title,
                        lastMessage = c.messages.lastOrNull { it.sender == s }?.text, recencyBoost = recency,
                    )
                }
            }
        }
        return out
    }

    private fun callContact(cmd: ParsedCommand): LocalExecResult {
        val who = cmd.slot<SlotValue.Contact>("contact")?.spoken?.trim()
            ?: return LocalExecResult.FallbackToAi("missing call target")
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.length >= 3 && who.all { it.isDigit() || it in setOf('+', '-', ' ', '(', ')') }) {
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return LocalExecResult.Done("Opening dialer for $who.", launchIntent = dial, stayInApp = true)
        }
        return LocalExecResult.FallbackToAi("contact call handled by OpenPhoneUse AI")
    }

    private fun readNotifications(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        if (!notifications.isAccessEnabled()) {
            return if (tx.hasAiBackend) {
                LocalExecResult.FallbackToAi("notification access not enabled")
            } else {
                LocalExecResult.Done("Notification access is off. Please enable it in Settings so I can read notifications.")
            }
        }
        if (!notifications.listenerConnected) {
            runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(context, PhoneUseNotificationListener::class.java),
                )
            }
        }
        val app = appSlot(cmd)
        val all = notifications.snapshot()
        val t = cmd.transcript.lowercase()
        val onlyNew = Regex("\\b(new|any|anything)\\b").containsMatchIn(t) && !t.contains("unread")
        val wantsEverything = app == null && t.contains("notification")

        var convs = when {
            app == "gmail" -> all.filter { it.app == "gmail" }
            app != null -> all.filter { it.app == app }
            wantsEverything -> all
            else -> all.filter { it.app in MESSAGING_APPS }
        }
        val who = cmd.slot<SlotValue.Contact>("contact")
        var whoName: String? = null
        if (who != null) {
            if (who.pronoun) {
                convs = convs.filter { it.key == tx.dialog.lastConvKey }
                whoName = tx.dialog.lastContactName
            } else {
                val m = ContactMatcher(candidates(convs).filter { it.id.startsWith("conv:") }).rank(who.spoken, app)
                val keys = m.filter { it.nameSim >= 0.84 }.map { it.candidate.id.removePrefix("conv:") }.toSet()
                convs = convs.filter { it.key in keys }
                whoName = m.firstOrNull()?.candidate?.displayName ?: who.spoken
            }
        }

        val spoken = when {
            app == "gmail" -> DigestSpeaker.emailDigest(convs, onlyNew, senderFilter = whoName)
            wantsEverything -> DigestSpeaker.genericDigest(convs)
            else -> DigestSpeaker.messagingDigest(convs, app, onlyNew, contactFilter = whoName)
        }
        notifications.markSpoken(spoken.readUpTo)
        val read = spoken.convKeys.mapNotNull { notifications.get(it) }
        val remaining = convs.filter { c -> c.key !in spoken.convKeys && c.unread.isNotEmpty() }
        tx.dialog.readQueue = remaining
        tx.dialog.readApp = app
        if (app == "gmail") tx.dialog.lastEmailKey = read.firstOrNull()?.key
        val single = read.singleOrNull { it.app != "gmail" }
        if (single != null) {
            tx.dialog.lastConvKey = single.key
            tx.dialog.lastContactName = single.title
        } else if (read.isNotEmpty() && read.first().app != "gmail") {
            tx.dialog.lastConvKey = read.first().key
            tx.dialog.lastContactName = read.first().title
        }

        var text = spoken.text
        val offer = single != null && single.app in MESSAGING_APPS && notifications.replyActionFor(single.key) != null && remaining.isEmpty()
        if (offer) {
            text += " Reply?"
            tx.dialog.offeredReply = true
        } else if (remaining.isNotEmpty()) {
            text += " Say continue for more."
        }
        return LocalExecResult.Done(text, listenAgain = offer || remaining.isNotEmpty())
    }

    private fun moreNotifications(tx: LocalTurnContext): LocalExecResult {
        val q = tx.dialog.readQueue
        if (q.isEmpty()) return LocalExecResult.Done("That's everything.")
        val spoken = if (tx.dialog.readApp == "gmail") {
            DigestSpeaker.emailDigest(q, false)
        } else {
            DigestSpeaker.messagingDigest(q, tx.dialog.readApp, false)
        }
        notifications.markSpoken(spoken.readUpTo)
        tx.dialog.readQueue = q.filter { it.key !in spoken.convKeys }
        spoken.convKeys.lastOrNull()?.let { k ->
            notifications.get(k)?.let { tx.dialog.lastConvKey = k; tx.dialog.lastContactName = it.title }
        }
        val suffix = if (tx.dialog.readQueue.isNotEmpty()) " Say continue for more." else ""
        return LocalExecResult.Done(spoken.text + suffix, listenAgain = tx.dialog.readQueue.isNotEmpty())
    }

    private suspend fun sendMessage(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        if (!notifications.isAccessEnabled()) return LocalExecResult.FallbackToAi("notification access off")
        val app = appSlot(cmd)
        if (app == "gmail") return LocalExecResult.FallbackToAi("gmail send handled by AI / connector")

        val rawMessage = cmd.text("message")?.trim()?.trim('"', '\'', '“', '”')?.takeIf { it.isNotBlank() }
        val convs = notifications.snapshot()
        val contactSlot = cmd.slot<SlotValue.Contact>("contact")

        val (conv, sim) = when {
            contactSlot == null || contactSlot.pronoun -> {
                val key = tx.dialog.lastConvKey ?: convs.filter { it.live && it.unread.isNotEmpty() && it.app in MESSAGING_APPS }.singleOrNull()?.key
                val c = key?.let { notifications.get(it) }
                    ?: return LocalExecResult.FallbackToAi("no active chat target for pronoun")
                c to 1.0
            }
            else -> {
                val m = ContactMatcher(candidates(convs)).resolve(
                    contactSlot.spoken,
                    app ?: "whatsapp",
                    setOfNotNull(tx.dialog.lastConvKey?.let { "conv:$it" }),
                )
                val best = m.best ?: return LocalExecResult.FallbackToAi("no notification chat for ${contactSlot.spoken}")
                if (!m.decided) {
                    val opts = m.alternatives.take(3)
                    tx.dialog.choice = LocalChoice(contactSlot.spoken, opts) { picked ->
                        store.learnAlias(contactSlot.spoken, picked.candidate.displayName)
                        val pickedConv = notifications.get(picked.candidate.id.removePrefix("conv:"))
                            ?: return@LocalChoice LocalExecResult.FallbackToAi("chat vanished")
                        deliverOrAskMessage(pickedConv, picked.nameSim, rawMessage, tx)
                    }
                    val desc = opts.joinToString(", or ") { o ->
                        val cand = o.candidate
                        cand.displayName + (cand.groupName?.let { " in $it" } ?: "")
                    }
                    return LocalExecResult.Done("Did you mean $desc?", listenAgain = true)
                }
                val c = notifications.get(best.candidate.id.removePrefix("conv:"))
                    ?: return LocalExecResult.FallbackToAi("chat vanished")
                c to best.nameSim
            }
        }
        return deliverOrAskMessage(conv, sim, rawMessage, tx)
    }

    private suspend fun deliverOrAskMessage(
        conv: Conversation,
        sim: Double,
        message: String?,
        tx: LocalTurnContext,
    ): LocalExecResult {
        if (message == null) {
            tx.dialog.slotRequest = LocalSlotRequest("message") { said ->
                deliverOrAskMessage(conv, sim, said, tx)
            }
            return LocalExecResult.Done("What should I say to ${conv.title}?", listenAgain = true)
        }
        val handle = notifications.replyActionFor(conv.key)
            ?: return LocalExecResult.FallbackToAi("no RemoteInput reply action for ${conv.title}")

        val mustConfirm = tx.confirmMode == ConfirmMode.CAREFUL || conv.isGroup || sim < 0.9
        if (mustConfirm) {
            val summary = if (conv.isGroup) {
                "Send \"$message\" to ${conv.title} (${Apps.displayName(conv.app)} group)"
            } else {
                "Send \"$message\" to ${conv.title} (${Apps.displayName(conv.app)})"
            }
            val ok = tx.gate.confirm(RiskCategory.SEND, summary)
            if (!ok) return LocalExecResult.Done("Cancelled.")
        }

        val (action, live) = handle
        return when (val r = ActionInvoker.reply(context, action, message)) {
            ActionInvoker.Outcome.Sent -> {
                tx.dialog.lastConvKey = conv.key
                tx.dialog.lastContactName = conv.title
                val confirmed = if (live) true else withTimeoutOrNull(2_500) {
                    notifications.posted.first { c ->
                        c.key == conv.key && c.messages.any { it.fromMe && it.text.trim() == message.trim() }
                    }
                } != null
                val replyText = if (confirmed) {
                    "Sent \"$message\" to ${conv.title}."
                } else {
                    "Sent \"$message\" to ${conv.title}, though I couldn't confirm delivery."
                }
                LocalExecResult.Done(replyText)
            }
            ActionInvoker.Outcome.Dead -> {
                notifications.evictCachedReply(conv.key)
                LocalExecResult.FallbackToAi("cached notification reply handle expired")
            }
            is ActionInvoker.Outcome.Failed -> {
                if (live) LocalExecResult.Done("Couldn't send: ${r.why}.")
                else LocalExecResult.FallbackToAi("reply action failed: ${r.why}")
            }
        }
    }

    private fun markRead(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        if (!notifications.isAccessEnabled()) return LocalExecResult.FallbackToAi("notification access off")
        val convs = notifications.snapshot().filter { it.live }
        val keys: List<String> = when {
            cmd.slot<SlotValue.Contact>("contact")?.takeIf { !it.pronoun } != null -> {
                val who = cmd.slot<SlotValue.Contact>("contact")!!.spoken
                ContactMatcher(candidates(convs)).rank(who).filter { it.nameSim >= 0.84 }
                    .map { it.candidate.id.removePrefix("conv:") }.distinct().take(1)
            }
            appSlot(cmd) != null -> convs.filter { it.app == appSlot(cmd) }.map { it.key }
            cmd.transcript.contains("all", true) || cmd.transcript.contains("everything", true) ->
                convs.filter { it.app in MESSAGING_APPS }.map { it.key }
            else -> listOfNotNull(tx.dialog.lastConvKey)
        }
        if (keys.isEmpty()) return LocalExecResult.Done("Which chat should I mark as read?", listenAgain = true)
        var n = 0
        for (k in keys) {
            val a = notifications.handlesFor(k)?.markRead ?: continue
            if (ActionInvoker.fire(context, a) == ActionInvoker.Outcome.Sent) n++
        }
        return if (n > 0) {
            LocalExecResult.Done(if (n == 1) "Marked as read." else "Marked $n chats as read.")
        } else {
            LocalExecResult.Done("That chat doesn't offer mark-as-read on its notification.")
        }
    }

    private fun clearNotifications(cmd: ParsedCommand): LocalExecResult {
        if (!notifications.isAccessEnabled()) return LocalExecResult.FallbackToAi("notification access off")
        val app = appSlot(cmd)
        val targets = notifications.snapshot().filter { it.live && (app == null || it.app == app) }
        if (app == null && targets.size > 3 && !cmd.transcript.contains("all", ignoreCase = true)) {
            return LocalExecResult.Done("Say 'clear all notifications' to dismiss everything.")
        }
        val sbnKeys = targets.mapNotNull { notifications.handlesFor(it.key)?.sbnKey }
        if (sbnKeys.isNotEmpty()) PhoneUseNotificationListener.cancel(sbnKeys)
        return LocalExecResult.Done(if (targets.isEmpty()) "Nothing to clear." else "Cleared ${targets.size} notifications.")
    }

    private fun archiveEmail(cmd: ParsedCommand, tx: LocalTurnContext): LocalExecResult {
        if (!notifications.isAccessEnabled()) return LocalExecResult.FallbackToAi("notification access off")
        val key = cmd.slot<SlotValue.Contact>("contact")?.let { who ->
            notifications.snapshot().filter { it.app == "gmail" && it.live }
                .maxByOrNull { ContactMatcher.similarity(ContactMatcher.keyForms(who.spoken), ContactMatcher.keyForms(it.title)) }?.key
        } ?: tx.dialog.lastEmailKey
            ?: notifications.snapshot().filter { it.app == "gmail" && it.live }.maxByOrNull { it.updatedAt }?.key
        val a = key?.let { notifications.handlesFor(it)?.archive }
        if (a != null) {
            return if (ActionInvoker.fire(context, a) == ActionInvoker.Outcome.Sent) {
                LocalExecResult.Done("Archived.")
            } else {
                LocalExecResult.Done("Couldn't archive it.")
            }
        }
        return if (key == null) LocalExecResult.Done("There's no email to archive.")
        else LocalExecResult.FallbackToAi("no archive notification action")
    }

    private fun appSlot(cmd: ParsedCommand): String? = (cmd.slots["app"] as? SlotValue.App)?.id
    private fun durationMs(cmd: ParsedCommand): Long? = (cmd.slots["duration"] as? SlotValue.Duration)?.ms

    companion object {
        private val MESSAGING_APPS = setOf(
            "whatsapp", "whatsapp_business", "sms", "telegram", "signal",
            "slack", "instagram", "messenger", "teams", "discord", "linkedin",
        )
        private val APP_SYNONYMS = mapOf(
            "call" to listOf("phone", "dialer"),
            "dialer" to listOf("phone"),
            "browser" to listOf("chrome", "internet"),
            "internet" to listOf("chrome", "browser"),
            "gallery" to listOf("photos", "gallery"),
            "mail" to listOf("gmail", "outlook"),
            "email" to listOf("gmail", "outlook"),
            "sms" to listOf("messages"),
            "texts" to listOf("messages"),
            "alarms" to listOf("clock"),
        )
    }
}

/** City / country -> timezone for zero-AI world clock queries. */
object Zones {
    private val EXTRA = mapOf(
        "lahore" to "Asia/Karachi", "karachi" to "Asia/Karachi", "islamabad" to "Asia/Karachi",
        "rawalpindi" to "Asia/Karachi", "peshawar" to "Asia/Karachi", "pakistan" to "Asia/Karachi",
        "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata",
        "bangalore" to "Asia/Kolkata", "india" to "Asia/Kolkata",
        "beijing" to "Asia/Shanghai", "china" to "Asia/Shanghai",
        "abu dhabi" to "Asia/Dubai", "uae" to "Asia/Dubai", "sharjah" to "Asia/Dubai",
        "mecca" to "Asia/Riyadh", "makkah" to "Asia/Riyadh", "madinah" to "Asia/Riyadh",
        "medina" to "Asia/Riyadh", "jeddah" to "Asia/Riyadh", "saudi arabia" to "Asia/Riyadh",
        "san francisco" to "America/Los_Angeles", "seattle" to "America/Los_Angeles", "california" to "America/Los_Angeles",
        "washington" to "America/New_York", "boston" to "America/New_York", "miami" to "America/New_York",
        "usa" to "America/New_York", "america" to "America/New_York",
        "texas" to "America/Chicago", "dallas" to "America/Chicago", "houston" to "America/Chicago",
        "uk" to "Europe/London", "england" to "Europe/London", "manchester" to "Europe/London", "birmingham" to "Europe/London",
        "canada" to "America/Toronto", "australia" to "Australia/Sydney", "japan" to "Asia/Tokyo",
        "korea" to "Asia/Seoul", "south korea" to "Asia/Seoul",
        "germany" to "Europe/Berlin", "france" to "Europe/Paris", "turkey" to "Europe/Istanbul",
        "qatar" to "Asia/Qatar", "malaysia" to "Asia/Kuala_Lumpur", "bangladesh" to "Asia/Dhaka",
        "afghanistan" to "Asia/Kabul", "iran" to "Asia/Tehran", "egypt" to "Africa/Cairo", "kuwait" to "Asia/Kuwait",
    )

    private val byCity: Map<String, String> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc") && !it.startsWith("SystemV") }
            .associateBy { it.substringAfterLast('/').replace('_', ' ').lowercase() } + EXTRA
    }

    fun find(place: String): ZoneId? {
        val q = place.lowercase().trim().removePrefix("the ")
        byCity[q]?.let { return ZoneId.of(it) }
        val best = byCity.keys.maxByOrNull { ContactMatcher.jaroWinkler(it, q) } ?: return null
        return if (ContactMatcher.jaroWinkler(best, q) >= 0.92) ZoneId.of(byCity.getValue(best)) else null
    }
}
