package com.theftguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Watches incoming SMS and reacts to exact keywords:
 *  - "Stolen"                      -> loud alarm + one location.
 *  - "Stolen_live"                 -> loud alarm + live location until unlock/timeout.
 *  - "silent_stolen"               -> silent, one location.
 *  - "silent_stolen_live"          -> silent, live location until "stop_stolen"/timeout.
 *  - "silent_stolen_<number>"      -> silent, one location texted to <number>.
 *  - "silent_stolen_live_<number>" -> silent, live location texted to <number>.
 *  - "stop_stolen"                 -> stop any running alarm / live run.
 * Matching is exact and case-sensitive (bar surrounding whitespace); anything else is ignored.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AlarmState.isArmed(context)) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        // A long SMS arrives in several parts; join them per sender before comparing.
        val bodies = parts
            .filterNotNull()
            .groupBy { it.displayOriginatingAddress.orEmpty() }
            .mapValues { (_, msgs) -> msgs.joinToString("") { it.displayMessageBody.orEmpty() } }
            .values

        // Pick the single most important command across all messages received.
        val command = bodies.mapNotNull { parse(it.trim()) }.minByOrNull { it.priority } ?: return
        command.run(context)
    }

    /** A recognised instruction, lower priority number = wins when several arrive at once. */
    private sealed class Command(val priority: Int) {
        abstract fun run(context: Context)

        object Stop : Command(0) {
            override fun run(context: Context) {
                Log.w(TAG, "Stop keyword received")
                AlarmService.stopAll(context)
            }
        }

        data class Loud(val live: Boolean) : Command(1) {
            override fun run(context: Context) {
                Log.w(TAG, "Loud trigger (live=$live)")
                AlarmService.start(context, silent = false, live = live)
            }
        }

        data class Silent(val live: Boolean, val target: String?) : Command(2) {
            override fun run(context: Context) {
                Log.w(TAG, "Silent trigger (live=$live, target=${target != null})")
                AlarmService.start(context, silent = true, live = live, target = target)
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        // silent_stolen, optional _live, optional _<number> (3-15 digits, optional leading +).
        private val SILENT = Regex("""^silent_stolen(_live)?(?:_(\+?\d{3,15}))?$""")

        private fun parse(text: String): Command? = when {
            text == "stop_stolen" -> Command.Stop
            text == AlarmState.TRIGGER_TEXT -> Command.Loud(live = false)
            text == "${AlarmState.TRIGGER_TEXT}_live" -> Command.Loud(live = true)
            else -> SILENT.matchEntire(text)?.let { m ->
                Command.Silent(live = m.groupValues[1].isNotEmpty(), target = m.groupValues[2].ifEmpty { null })
            }
        }

        fun isTrigger(body: String): Boolean = body.trim() == AlarmState.TRIGGER_TEXT

        fun isSilentTrigger(body: String): Boolean = SILENT.matches(body.trim())
    }
}
