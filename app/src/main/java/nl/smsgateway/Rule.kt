package nl.smsgateway

import org.json.JSONObject
import java.util.UUID

/**
 * Eén doorstuurregel: welke sms'jes gaan naar welke webhook.
 * Een sms gaat naar ALLE actieve regels die matchen.
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var type: Type = Type.ALL,
    /** Komma-gescheiden nummers of woorden */
    var value: String = "",
    var url: String = "",
    var headerName: String = "",
    var headerValue: String = "",
    var enabled: Boolean = true,
    /** Optioneel eigen JSON-sjabloon met {{placeholders}}; leeg = standaard-payload */
    var template: String = "",
) {
    enum class Type(val label: String) {
        ALL("Alle sms"),
        SENDER("Van bepaald nummer / afzender"),
        KEYWORD("Bevat bepaald woord"),
    }

    private fun values() = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun matches(from: String, body: String): Boolean {
        if (!enabled || url.isBlank()) return false
        return when (type) {
            Type.ALL -> true
            Type.SENDER -> values().any { senderMatches(from, it) }
            Type.KEYWORD -> values().any { body.contains(it, ignoreCase = true) }
        }
    }

    fun describe(): String = when (type) {
        Type.ALL -> "Alle sms"
        Type.SENDER -> "Van: $value"
        Type.KEYWORD -> "Bevat: $value"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name).put("value", value)
        .put("url", url).put("hn", headerName).put("hv", headerValue).put("enabled", enabled).put("tpl", template)

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            type = runCatching { Type.valueOf(o.optString("type")) }.getOrDefault(Type.ALL),
            value = o.optString("value"),
            url = o.optString("url"),
            headerName = o.optString("hn"),
            headerValue = o.optString("hv"),
            enabled = o.optBoolean("enabled", true),
            template = o.optString("tpl", ""),
        )

        /**
         * Vergelijkt telefoonnummers ongeacht notatie:
         * 0612345678, +31 6 1234 5678 en 0031612345678 zijn gelijk (laatste 9 cijfers).
         * Afzendernamen zoals "ING" of "PostNL" worden zonder hoofdlettergevoeligheid vergeleken.
         */
        fun senderMatches(from: String, pattern: String): Boolean {
            val a = from.filter { it.isDigit() }
            val b = pattern.filter { it.isDigit() }
            if (a.length >= 6 && b.length >= 6) {
                val n = minOf(9, a.length, b.length)
                return a.takeLast(n) == b.takeLast(n)
            }
            return from.trim().equals(pattern.trim(), ignoreCase = true)
        }
    }
}
