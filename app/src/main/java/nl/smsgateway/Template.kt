package nl.smsgateway

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Eigen JSON-payload per regel. Placeholders tussen {{ }} worden vervangen;
 * tekst wordt automatisch JSON-veilig gemaakt (quotes, enters, emoji).
 *
 * Voorbeeld: {"tekst": "{{message}}", "afzender": "{{from}}"}
 */
object Template {

    val PLACEHOLDERS = listOf(
        "message", "from", "timestamp", "received_at",
        "sim_slot", "subscription_id", "rule", "device", "test"
    )

    fun render(template: String, values: Map<String, Any>): String {
        var out = template
        values.forEach { (key, v) ->
            val replacement = when (v) {
                is Number, is Boolean -> v.toString()
                else -> JSONObject.quote(v.toString()).let { it.substring(1, it.length - 1) }
            }
            out = out.replace("{{$key}}", replacement)
        }
        return out
    }

    /** Geeft null terug als het sjabloon geldige JSON oplevert, anders een foutmelding. */
    fun validate(template: String): String? {
        val sample = mapOf<String, Any>(
            "from" to "+31612345678", "message" to "Test \"met\" quotes\nen enter",
            "timestamp" to 1L, "received_at" to "2026-01-01T00:00:00+01:00",
            "subscription_id" to 1, "sim_slot" to 0, "rule" to "test",
            "device" to "toestel", "test" to true
        )
        val rendered = render(template, sample)
        Regex("\\{\\{(\\w+)}}").find(rendered)?.let {
            return "Onbekende placeholder: {{${it.groupValues[1]}}}"
        }
        return try {
            when (JSONTokener(rendered).nextValue()) {
                is JSONObject, is JSONArray -> null
                else -> "Sjabloon moet een JSON-object zijn, bv. {\"tekst\": \"{{message}}\"}"
            }
        } catch (e: JSONException) {
            "Ongeldige JSON: ${e.message}"
        }
    }
}
