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
        ): String {
            val values = linkedMapOf<String, Any>(
                "from" to from,
                "message" to body,
                "timestamp" to ts,
                "received_at" to Http.iso(ts),
                "subscription_id" to subId,
                "sim_slot" to slot,
                "rule" to rule.name,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "test" to test,
            )
            if (rule.template.isNotBlank()) return Template.render(rule.template, values)

            val json = JSONObject().put("type", "sms")
            values.forEach { (k, v) -> json.put(k, v) }
            return json.toString()
        }
}
