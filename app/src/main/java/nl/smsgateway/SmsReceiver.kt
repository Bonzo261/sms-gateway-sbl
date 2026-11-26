package nl.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import org.json.JSONObject

/**
 * Vangt binnenkomende sms'jes op en stuurt ze naar elke regel die matcht.
 * Lange sms'jes (meerdere delen) worden per afzender samengevoegd.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = Prefs(context)
        if (!prefs.enabled) return
        val rules = prefs.rules
        if (rules.isEmpty()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val extras = intent.extras
        val subId = extras?.getInt("subscription", -1) ?: -1
        val slot = extras?.getInt("slot", extras.getInt("simSlot", -1)) ?: -1

        messages.groupBy { it.displayOriginatingAddress ?: "onbekend" }
            .forEach { (from, parts) ->
                val body = parts.joinToString("") { it.displayMessageBody ?: "" }
                val ts = parts.minOf { it.timestampMillis }
                rules.filter { it.matches(from, body) }.forEach { rule ->
                    val payload = buildPayload(from, body, ts, subId, slot, rule, test = false)
                    ForwardWorker.enqueue(context, rule, payload)
                }
            }
    }

    companion object {
        fun buildPayload(
            from: String, body: String, ts: Long, subId: Int, slot: Int,
            rule: Rule, test: Boolean
        ): String = JSONObject()
            .put("type", "sms")
            .put("from", from)
            .put("message", body)
            .put("timestamp", ts)
            .put("received_at", Http.iso(ts))
            .put("subscription_id", subId)
            .put("sim_slot", slot)
            .put("rule", rule.name)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("test", test)
            .toString()
    }
}
