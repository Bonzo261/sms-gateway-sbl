package nl.smsgateway

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Stuurt één payload naar één webhook. WorkManager wacht op netwerk en
 * probeert het opnieuw bij fouten, ook na een herstart van de telefoon.
 */
class ForwardWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val url = inputData.getString(K_URL) ?: return Result.failure()
        val label = inputData.getString(K_LABEL) ?: ""
        val payload = inputData.getString(K_PAYLOAD) ?: return Result.failure()

        return try {
            val code = Http.postJson(
                url, payload,
                inputData.getString(K_HN) ?: "", inputData.getString(K_HV) ?: ""
            )
            when {
                code in 200..299 -> {
                    prefs.lastStatus = "✅ ${Http.now()} – [$label] doorgestuurd (HTTP $code)"
                    Result.success()
                }
                code in 400..499 && code != 408 && code != 429 -> {
                    prefs.lastStatus = "❌ ${Http.now()} – [$label] HTTP $code, niet opnieuw geprobeerd"
                    Result.failure()
                }
                else -> retry(prefs, label, "HTTP $code")
            }
        } catch (e: Exception) {
            retry(prefs, label, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private fun retry(prefs: Prefs, label: String, reason: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) {
            prefs.lastStatus = "⏳ ${Http.now()} – [$label] poging ${runAttemptCount + 1} mislukt ($reason), opnieuw…"
            Result.retry()
        } else {
            prefs.lastStatus = "❌ ${Http.now()} – [$label] opgegeven na $MAX_ATTEMPTS pogingen ($reason)"
            Result.failure()
        }

    companion object {
        private const val K_URL = "url"
        private const val K_HN = "hn"
        private const val K_HV = "hv"
        private const val K_PAYLOAD = "payload"
        private const val K_LABEL = "label"
        private const val MAX_ATTEMPTS = 20

        fun enqueue(context: Context, rule: Rule, payload: String) {
            val data = workDataOf(
                K_URL to rule.url, K_HN to rule.headerName, K_HV to rule.headerValue,
                K_PAYLOAD to payload, K_LABEL to rule.name
            )
            val req = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("forward")
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
