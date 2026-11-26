package nl.smsgateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewParent
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var tvSubtitle: TextView
    private lateinit var chipPermission: Chip
    private lateinit var chipBattery: Chip
    private lateinit var swEnabled: MaterialSwitch
    private lateinit var rulesContainer: LinearLayout
    private lateinit var tvStatus: TextView

    private lateinit var swHeartbeat: MaterialSwitch
    private lateinit var etHbUrl: EditText
    private lateinit var etHbInterval: EditText
    private lateinit var etHbHName: EditText
    private lateinit var etHbHValue: EditText
    private lateinit var tvHbStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvSubtitle = findViewById(R.id.tvSubtitle)
        chipPermission = findViewById(R.id.chipPermission)
        chipBattery = findViewById(R.id.chipBattery)
        swEnabled = findViewById(R.id.swEnabled)
        rulesContainer = findViewById(R.id.rulesContainer)
        tvStatus = findViewById(R.id.tvStatus)
        swHeartbeat = findViewById(R.id.swHeartbeat)
        etHbUrl = findViewById(R.id.etHbUrl)
        etHbInterval = findViewById(R.id.etHbInterval)
        etHbHName = findViewById(R.id.etHbHeaderName)
        etHbHValue = findViewById(R.id.etHbHeaderValue)
        tvHbStatus = findViewById(R.id.tvHbStatus)

        applyInsets()

        findViewById<TextView>(R.id.tvVersion).text = runCatching {
            "versie " + packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("")

        // --- Status-chips ---
        chipPermission.setOnClickListener {
            if (!hasSmsPermission()) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS) || !askedBefore) {
                    ensureSmsPermission()
                } else openAppSettings()
            }
        }
        chipBattery.setOnClickListener { askIgnoreBattery() }

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
            refreshStatus()
        }
        findViewById<MaterialButton>(R.id.btnAddRule).setOnClickListener { editRule(null) }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { sendTest() }

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
            refreshStatus()
        }
        findViewById<MaterialButton>(R.id.btnHbSave).setOnClickListener {
            if (saveHeartbeatFields()) {
                if (prefs.hbEnabled) HeartbeatWorker.startNow(this)
                toast("Heartbeat opgeslagen")
                refreshStatus()
            }
        }
        findViewById<MaterialButton>(R.id.btnHbNow).setOnClickListener {
            if (!saveHeartbeatFields()) return@setOnClickListener
            if (!prefs.hbEnabled) { toast("Zet de heartbeat eerst aan"); return@setOnClickListener }
            HeartbeatWorker.startNow(this)
            toast("Ping verstuurd")
        }

        HeartbeatWorker.ensure(this)
        ensureSmsPermission()
    }

    /** Houdt rekening met statusbalk, notch, navigatiebalk en toetsenbord. */
    private fun applyInsets() {
        val header = findViewById<View>(R.id.header)
        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(20))
            scroll.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        renderRules()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    // ---------- Status ----------

    private fun hasSmsPermission() =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val smsOk = hasSmsPermission()
        val battOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        styleChip(chipPermission, smsOk, if (smsOk) "✓  SMS-toestemming" else "!  SMS-toestemming ontbreekt")
        styleChip(chipBattery, battOk, if (battOk) "✓  Batterij" else "!  Batterij-optimalisatie aan")

        val active = prefs.rules.count { it.enabled }
        tvSubtitle.text = buildString {
            append(if (prefs.enabled) "Doorsturen aan" else "Doorsturen uit")
            append(" · $active ${if (active == 1) "regel" else "regels"}")
            if (prefs.hbEnabled) append(" · heartbeat ${prefs.hbInterval} min")
        }
        tvStatus.text = prefs.lastStatus
        tvHbStatus.text = prefs.hbStatus
    }

    private fun styleChip(chip: Chip, ok: Boolean, text: String) {
        chip.text = text
        chip.chipBackgroundColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (ok) R.color.ok_container else R.color.warn_container)
        )
        chip.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.on_ok_container else R.color.on_warn_container)
        )
    }

    // ---------- Regels ----------

    private fun renderRules() {
        rulesContainer.removeAllViews()
        val rules = prefs.rules
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "Nog geen regels. Voeg er een toe, bijvoorbeeld ‘Alle sms’ naar je webhook."
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, 0, 0, dp(8))
            })
            return
        }
        val inflater = LayoutInflater.from(this)
        rules.forEach { r ->
            val item = inflater.inflate(R.layout.item_rule, rulesContainer, false)
            item.findViewById<TextView>(R.id.tvName).text = r.name
            item.findViewById<TextView>(R.id.tvDesc).text =
                r.describe() + if (r.template.isNotBlank()) "  ·  eigen JSON" else ""
            item.findViewById<TextView>(R.id.tvUrl).text = r.url
            item.findViewById<View>(R.id.dot).backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (r.enabled) R.color.dot_on else R.color.dot_off)
            )
            item.alpha = if (r.enabled) 1f else 0.6f
            item.setOnClickListener { editRule(r) }
            rulesContainer.addView(item)
        }
    }

    private fun editRule(existing: Rule?) {
        val rule = existing?.copy() ?: Rule()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_rule, null)

        val etName = view.findViewById<EditText>(R.id.etName)
        val actType = view.findViewById<AutoCompleteTextView>(R.id.actType)
        val tilValue = view.findViewById<TextInputLayout>(R.id.tilValue)
        val etValue = view.findViewById<EditText>(R.id.etValue)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etHN = view.findViewById<EditText>(R.id.etHName)
        val etHV = view.findViewById<EditText>(R.id.etHValue)
        val tilTpl = view.findViewById<TextInputLayout>(R.id.tilTpl)
        val etTpl = view.findViewById<EditText>(R.id.etTpl)
        val swRule = view.findViewById<MaterialSwitch>(R.id.swRuleEnabled)

        etName.setText(rule.name)
        etValue.setText(rule.value)
        etUrl.setText(rule.url)
        etHN.setText(rule.headerName)
        etHV.setText(rule.headerValue)
        etTpl.setText(rule.template)
        swRule.isChecked = rule.enabled
        tilTpl.helperText = "Leeg = standaard. Placeholders: " +
            Template.PLACEHOLDERS.joinToString(" ") { "{{$it}}" }
        view.findViewById<MaterialButton>(R.id.btnTplExample).setOnClickListener {
            etTpl.setText("{\"tekst\": \"{{message}}\"}")
        }

        val types = Rule.Type.values()
        var selectedType = rule.type
        actType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types.map { it.label }))
        fun applyType(t: Rule.Type) {
            selectedType = t
            when (t) {
                Rule.Type.ALL -> tilValue.visibility = View.GONE
                Rule.Type.SENDER -> {
                    tilValue.visibility = View.VISIBLE
                    tilValue.hint = "Nummer(s) of afzender"
                    tilValue.helperText = "Komma-gescheiden, bv. 0612345678, ING"
                }
                Rule.Type.KEYWORD -> {
                    tilValue.visibility = View.VISIBLE
                    tilValue.hint = "Woord(en)"
                    tilValue.helperText = "Komma-gescheiden, bv. alarm, code"
                }
            }
        }
        actType.setText(rule.type.label, false)
        applyType(rule.type)
        actType.setOnItemClickListener { _, _, pos, _ -> applyType(types[pos]) }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Nieuwe regel" else "Regel bewerken")
            .setView(view)
            .setPositiveButton("Opslaan", null)
            .setNegativeButton("Annuleren", null)
        if (existing != null) builder.setNeutralButton("Verwijderen") { _, _ -> confirmDelete(existing) }
        val dlg = builder.create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                listOf(etValue, etUrl, etTpl).forEach { it.showError(null) }
                val value = etValue.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val tpl = etTpl.text.toString().trim()
                if (!Http.isValidUrl(url)) {
                    etUrl.showError("Geldige http(s) URL nodig"); return@setOnClickListener
                }
                if (selectedType != Rule.Type.ALL && value.isEmpty()) {
                    etValue.showError("Vul een nummer of woord in"); return@setOnClickListener
                }
                if (tpl.isNotEmpty()) {
                    Template.validate(tpl)?.let { etTpl.showError(it); return@setOnClickListener }
                }
                rule.type = selectedType
                rule.value = if (selectedType == Rule.Type.ALL) "" else value
                rule.url = url
                rule.headerName = etHN.text.toString().trim()
                rule.headerValue = etHV.text.toString().trim()
                rule.template = tpl
                rule.enabled = swRule.isChecked
                rule.name = etName.text.toString().trim().ifEmpty { rule.describe() }

                val list = prefs.rules
                val idx = list.indexOfFirst { it.id == rule.id }
                if (idx >= 0) list[idx] = rule else list.add(rule)
                prefs.rules = list
                dlg.dismiss()
                renderRules()
                refreshStatus()
            }
        }
        dlg.show()
    }

    private fun confirmDelete(rule: Rule) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Regel verwijderen?")
            .setMessage("‘${rule.name}’ wordt verwijderd.")
            .setPositiveButton("Verwijderen") { _, _ ->
                prefs.rules = prefs.rules.filterNot { it.id == rule.id }.toMutableList()
                if (prefs.rules.none { it.enabled }) {
                    prefs.enabled = false
                    swEnabled.isChecked = false
                }
                renderRules()
                refreshStatus()
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
        etHbUrl.showError(null)
        etHbInterval.showError(null)
        val url = etHbUrl.text.toString().trim()
        val interval = etHbInterval.text.toString().toIntOrNull()
        if (!Http.isValidUrl(url)) { etHbUrl.showError("Geldige http(s) URL nodig"); return false }
        if (interval == null || interval < 1) { etHbInterval.showError("Minimaal 1 minuut"); return false }
        prefs.hbUrl = url
        prefs.hbInterval = interval
        prefs.hbHeaderName = etHbHName.text.toString()
        prefs.hbHeaderValue = etHbHValue.text.toString()
        return true
    }

    // ---------- Toestemmingen ----------

    private var askedBefore = false

    private fun ensureSmsPermission() {
        if (!hasSmsPermission()) {
            askedBefore = true
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Zonder SMS-toestemming werkt de gateway niet. Tik op de rode chip om het te regelen.")
        }
        refreshStatus()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
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

    // ---------- Hulpjes ----------

    /** Toont de fout netjes onder het Material-invoerveld. */
    private fun EditText.showError(msg: String?) {
        var p: ViewParent? = parent
        while (p != null && p !is TextInputLayout) p = p.parent
        if (p is TextInputLayout) {
            p.error = msg
            p.isErrorEnabled = msg != null
        } else error = msg
    }

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
