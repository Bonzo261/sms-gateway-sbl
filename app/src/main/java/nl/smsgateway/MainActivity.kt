package nl.smsgateway

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var swEnabled: Switch
    private lateinit var rulesContainer: LinearLayout
    private lateinit var tvPermission: TextView
    private lateinit var tvStatus: TextView

    private lateinit var swHeartbeat: Switch
    private lateinit var etHbUrl: EditText
    private lateinit var etHbInterval: EditText
    private lateinit var etHbHName: EditText
    private lateinit var etHbHValue: EditText
    private lateinit var tvHbStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvPermission = findViewById(R.id.tvPermission)
        tvStatus = findViewById(R.id.tvStatus)
        swEnabled = findViewById(R.id.swEnabled)
        rulesContainer = findViewById(R.id.rulesContainer)

        swHeartbeat = findViewById(R.id.swHeartbeat)
        etHbUrl = findViewById(R.id.etHbUrl)
        etHbInterval = findViewById(R.id.etHbInterval)
        etHbHName = findViewById(R.id.etHbHeaderName)
        etHbHValue = findViewById(R.id.etHbHeaderValue)
        tvHbStatus = findViewById(R.id.tvHbStatus)

        // --- Doorsturen ---
        swEnabled.isChecked = prefs.enabled
        swEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && prefs.rules.none { it.enabled }) {
                toast("Voeg eerst minstens één actieve regel toe")
                swEnabled.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.enabled = checked
            if (checked) ensureSmsPermission()
        }
        findViewById<Button>(R.id.btnAddRule).setOnClickListener { editRule(null) }
        findViewById<Button>(R.id.btnTest).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askIgnoreBattery() }

        // --- Heartbeat ---
        swHeartbeat.isChecked = prefs.hbEnabled
        etHbUrl.setText(prefs.hbUrl)
        etHbInterval.setText(prefs.hbInterval.toString())
        etHbHName.setText(prefs.hbHeaderName)
        etHbHValue.setText(prefs.hbHeaderValue)

        swHeartbeat.setOnCheckedChangeListener { _, checked ->
            if (checked && !saveHeartbeatFields()) {
                swHeartbeat.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.hbEnabled = checked
            if (checked) HeartbeatWorker.startNow(this) else HeartbeatWorker.stop(this)
        }
        findViewById<Button>(R.id.btnHbSave).setOnClickListener {
            if (saveHeartbeatFields()) {
                if (prefs.hbEnabled) HeartbeatWorker.startNow(this)
                toast("Heartbeat opgeslagen")
            }
        }
        findViewById<Button>(R.id.btnHbNow).setOnClickListener {
            if (!saveHeartbeatFields()) return@setOnClickListener
            if (!prefs.hbEnabled) { toast("Zet de heartbeat eerst aan"); return@setOnClickListener }
            HeartbeatWorker.startNow(this)
            toast("Ping verstuurd")
        }

        HeartbeatWorker.ensure(this)
        ensureSmsPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    private fun refresh() {
        renderRules()
        refreshStatus()
    }

    private fun refreshStatus() {
        val granted = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val battOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        tvPermission.text = buildString {
            append(if (granted) "✅ SMS-toestemming verleend" else "⚠️ SMS-toestemming ontbreekt")
            append("\n")
            append(if (battOk) "✅ Batterij-optimalisatie uit" else "⚠️ Batterij-optimalisatie aan (kan vertragen)")
        }
        tvStatus.text = "Laatste status:\n" + prefs.lastStatus
        tvHbStatus.text = "Laatste heartbeat:\n" + prefs.hbStatus
    }

    // ---------- Regels ----------

    private fun renderRules() {
        rulesContainer.removeAllViews()
        val rules = prefs.rules
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "Nog geen regels. Voeg er een toe, bv. ‘Alle sms’ naar je webhook."
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        rules.forEach { r ->
            rulesContainer.addView(TextView(this).apply {
                text = "${if (r.enabled) "🟢" else "⚪"}  ${r.name}\n     ${r.describe()}\n     → ${r.url}"
                textSize = 15f
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { editRule(r) }
            })
        }
    }

    private fun editRule(existing: Rule?) {
        val rule = existing?.copy() ?: Rule()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = layout.addView(TextView(this).apply { text = t; setPadding(0, dp(10), 0, 0) })
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply { this.hint = hint; setText(value); inputType = type }.also { layout.addView(it) }

        label("Naam")
        val etName = field("bv. Bank, Alarm, Alles", rule.name)
        label("Wanneer doorsturen")
        val spType = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                Rule.Type.values().map { it.label }
            )
            setSelection(rule.type.ordinal)
        }
        layout.addView(spType)
        val etValue = field("", rule.value)
        label("Webhook URL")
        val etUrl = field("https://…", rule.url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        label("Extra header (optioneel)")
        val etHN = field("Authorization", rule.headerName)
        val etHV = field("Bearer jouw-token", rule.headerValue,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        label("JSON-sjabloon (optioneel – leeg = standaard)")
        val etTpl = EditText(this).apply {
            hint = "{\"tekst\": \"{{message}}\"}"
            setText(rule.template)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(etTpl)
        layout.addView(TextView(this).apply {
            text = "Placeholders: " + Template.PLACEHOLDERS.joinToString(" ") { "{{$it}}" }
            textSize = 12f
        })
        layout.addView(Button(this).apply {
            text = "Voorbeeld: alleen tekst"
            setOnClickListener { etTpl.setText("{\"tekst\": \"{{message}}\"}") }
        })
        val cbEnabled = CheckBox(this).apply { text = "Regel actief"; isChecked = rule.enabled }
        layout.addView(cbEnabled)

        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                when (Rule.Type.values()[pos]) {
                    Rule.Type.ALL -> etValue.visibility = View.GONE
                    Rule.Type.SENDER -> {
                        etValue.visibility = View.VISIBLE
                        etValue.hint = "Nummer(s)/afzender, komma-gescheiden: 0612345678, ING"
                        etValue.inputType = InputType.TYPE_CLASS_TEXT
                    }
                    Rule.Type.KEYWORD -> {
                        etValue.visibility = View.VISIBLE
                        etValue.hint = "Woord(en), komma-gescheiden: alarm, code"
                        etValue.inputType = InputType.TYPE_CLASS_TEXT
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nieuwe regel" else "Regel bewerken")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Opslaan", null)
            .setNegativeButton("Annuleren", null)
        if (existing != null) builder.setNeutralButton("Verwijderen") { _, _ -> confirmDelete(existing) }
        val dlg = builder.create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = Rule.Type.values()[spType.selectedItemPosition]
                val value = etValue.text.toString().trim()
                val url = etUrl.text.toString().trim()
                when {
                    !Http.isValidUrl(url) -> { etUrl.error = "Geldige http(s) URL nodig"; return@setOnClickListener }
                    type != Rule.Type.ALL && value.isEmpty() -> {
                        etValue.error = "Vul een nummer of woord in"; return@setOnClickListener
                    }
                }
                val tpl = etTpl.text.toString().trim()
                if (tpl.isNotEmpty()) {
                    Template.validate(tpl)?.let { etTpl.error = it; return@setOnClickListener }
                }
                rule.template = tpl
                rule.type = type
                rule.value = if (type == Rule.Type.ALL) "" else value
                rule.url = url
                rule.headerName = etHN.text.toString().trim()
                rule.headerValue = etHV.text.toString().trim()
                rule.enabled = cbEnabled.isChecked
                rule.name = etName.text.toString().trim().ifEmpty { rule.describe() }

                val list = prefs.rules
                val idx = list.indexOfFirst { it.id == rule.id }
                if (idx >= 0) list[idx] = rule else list.add(rule)
                prefs.rules = list
                dlg.dismiss()
                renderRules()
            }
        }
        dlg.show()
    }

    private fun confirmDelete(rule: Rule) {
        AlertDialog.Builder(this)
            .setMessage("Regel ‘${rule.name}’ verwijderen?")
            .setPositiveButton("Verwijderen") { _, _ ->
                prefs.rules = prefs.rules.filterNot { it.id == rule.id }.toMutableList()
                if (prefs.rules.none { it.enabled }) { prefs.enabled = false; swEnabled.isChecked = false }
                renderRules()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun sendTest() {
        val active = prefs.rules.filter { it.enabled }
        if (active.isEmpty()) { toast("Geen actieve regels"); return }
        val now = System.currentTimeMillis()
        active.forEach { r ->
            val payload = SmsReceiver.buildPayload(
                "+31600000000", "Testbericht van SMS Gateway", now, -1, -1, r, test = true
            )
            ForwardWorker.enqueue(this, r, payload)
        }
        toast("Test verstuurd naar ${active.size} regel(s)")
    }

    // ---------- Heartbeat ----------

    private fun saveHeartbeatFields(): Boolean {
        val url = etHbUrl.text.toString().trim()
        val interval = etHbInterval.text.toString().toIntOrNull()
        if (!Http.isValidUrl(url)) { etHbUrl.error = "Geldige http(s) URL nodig"; return false }
        if (interval == null || interval < 1) { etHbInterval.error = "Minimaal 1 minuut"; return false }
        prefs.hbUrl = url
        prefs.hbInterval = interval
        prefs.hbHeaderName = etHbHName.text.toString()
        prefs.hbHeaderValue = etHbHValue.text.toString()
        return true
    }

    // ---------- Overig ----------

    private fun ensureSmsPermission() {
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Zonder SMS-toestemming werkt de gateway niet. Zie App-info → Toestemmingen.")
        }
        refreshStatus()
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
