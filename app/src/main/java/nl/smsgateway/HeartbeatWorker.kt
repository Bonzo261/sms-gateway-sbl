package nl.smsgateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.work.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Stuurt op een vast interval een "ik leef nog"-ping naar de heartbeat-URL.
 * Na elke ping plant hij zichzelf opnieuw in. Overleeft een herstart van de telefoon.
 */
class HeartbeatWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.hbEnabled || prefs.hbUrl.isBlank()) return Result.success()

        try {
            val code = Http.postJson(prefs.hbUrl, payload(prefs), prefs.hbHeaderName, prefs.hbHeaderValue)
            prefs.hbStatus = if (code in 200..299) "✅ ${Http.now()} – heartbeat OK (HTTP $code)"
            else "❌ ${Http.now()} – heartbeat HTTP $code"
        } catch (e: Exception) {
            prefs.hbStatus = "❌ ${Http.now()} – heartbeat mislukt (${e.javaClass.simpleName})"
        }

        // Volgende heartbeat inplannen (als laatste stap).
        schedule(applicationContext, prefs.hbInterval.toLong(), ExistingWorkPolicy.REPLACE)
        return Result.success()
    }

    private fun payload(prefs: Prefs): String {
        val ctx = applicationContext
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("type", "heartbeat")
            .put("timestamp", now)
            .put("sent_at", Http.iso(now))
            .put("interval_minutes", prefs.hbInterval)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("battery_level", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("charging", bm?.isCharging ?: false)
            .put("forwarding_enabled", prefs.enabled)
            .put("active_rules", prefs.rules.count { it.enabled })
            .put(
                "sms_permission",
                ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            )
            .toString()
    }

    companion object {
        private const val NAME = "heartbeat"

        fun schedule(ctx: Context, delayMinutes: Long, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, req)
        }

        /** Nu direct een ping sturen en daarna het interval aanhouden. */
        fun startNow(ctx: Context) = schedule(ctx, 0, ExistingWorkPolicy.REPLACE)

        /** Zorgt dat er een heartbeat gepland staat, zonder een bestaande te verstoren. */
        fun ensure(ctx: Context) {
            val p = Prefs(ctx)
            if (p.hbEnabled && p.hbUrl.isNotBlank()) schedule(ctx, p.hbInterval.toLong(), ExistingWorkPolicy.KEEP)
        }

        fun stop(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}
