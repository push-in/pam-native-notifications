package dev.pam.notifications

import android.annotation.SuppressLint
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import dev.pam.nativeapp.push.BackgroundPush
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

internal enum class TokenScheduleResult(val code: Int) { SCHEDULED(1), UNCHANGED(2), EMPTY(3) }

/** One durable latest token; no Activity, PHP runtime, periodic job or polling. */
internal object PamPushTokenRegistration {
    private const val PREFERENCES = "pam-native-push-token-registration"
    private const val STATE = "state"
    internal const val JOB_ID = 0x50414d54
    private val lock = Any()
    internal val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
        { runnable -> Thread(runnable, "pam-push-token").apply { isDaemon = true } })

    private var active: Cancellation? = null
    private val intakeLock = Any()
    private data class Incoming(val context: Application, val token: String, val epoch: Long, val finished: () -> Unit)
    private var incoming: Incoming? = null
    private var receiving = false
    private val intake = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
        { runnable -> Thread(runnable, "pam-push-token-store").apply { isDaemon = true } })

    /** One reconciliation at process boot/foreground, never a timer or poll. */
    fun resume(context: Context) {
        try { worker.execute { runCatching { schedule(context.applicationContext) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* Existing worker will reconcile on completion. */ }
    }

    internal fun cancelWorker(task: FutureTask<Unit>?) {
        task?.cancel(true)
        if (task != null) worker.remove(task)
    }

    fun registrationEpoch(context: Context): Long = transaction(context) { it.registrationEpoch }

    /** Coalesce bursts while a goAsync lease still protects the latest write. */
    fun receive(context: Context, token: String, expectedEpoch: Long? = null, finished: () -> Unit) {
        val entry = Incoming(context.applicationContext as Application, token, expectedEpoch ?: registrationEpoch(context), finished)
        synchronized(intakeLock) {
            incoming?.finished?.invoke()
            incoming = entry
            if (receiving) return
            receiving = true
            intake.execute {
                while (true) {
                    val next = synchronized(intakeLock) {
                        incoming.also { incoming = null; if (it == null) receiving = false }
                    } ?: break
                    try {
                        transaction(next.context) {
                            if (it.registrationEpoch == next.epoch) {
                                it.token(next.token)
                                it.reconcile(credentials(next.context))
                            }
                        }
                        schedule(next.context)
                    } catch (_: Exception) {
                        // No tokens/configuration in logs or exception reports.
                    } finally { next.finished() }
                }
            }
        }
    }

    fun started(cancel: Cancellation) = synchronized(lock) { active = cancel }
    fun stopped(cancel: Cancellation) = synchronized(lock) { if (active === cancel) active = null }

    private fun credentials(context: Context): (String) -> String? = { PamNotificationCredentials.token(context, it) }
    private fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    // KTX edit(commit=true) returns Unit; durable queue writes must check commit failure.
    @SuppressLint("UseKtx")
    private fun <T> transaction(context: Context, work: (TokenRegistrationState) -> T): T = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = prefs.getString(STATE, null)
        val state = TokenRegistrationState.from(previous)
        val result = work(state)
        val next = state.json()
        if (next != previous) check(prefs.edit().putString(STATE, next).commit()) { "Token registration persistence failed" }
        result
    }

    fun configure(context: Context, endpoint: String, accounts: String) {
        require(endpoint.toByteArray(Charsets.UTF_8).size <= 65_536 && accounts.toByteArray(Charsets.UTF_8).size <= 16_384) { "Token registration configuration too large" }
        val spec = JSONObject(endpoint)
        val headers = spec.optJSONObject("headers") ?: error("Token registration requires credential authorization")
        val auth = headers.keys().asSequence().filter { it.equals("Authorization", ignoreCase = true) }.toList()
        require(auth.size == 1 && headers.getString(auth.single()) == "Bearer {credential:account}") { "Token registration requires account credentials" }
        require(spec.getString("method") in setOf("POST", "PUT", "PATCH", "DELETE"))
        require(!spec.getString("url").contains("{credential:") && !spec.opt("body").toString().contains("{credential:"))
        val ids = JSONArray(accounts)
        require(ids.length() <= 64) { "Too many token registration accounts" }
        val normalized = (0 until ids.length()).map { index ->
            PamNotificationCredentials.normalize(ids.getString(index)).also {
                require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 128) { "Invalid token registration account" }
            }
        }.distinct().sorted()
        transaction(context) { it.configure(canonical(spec), normalized); it.reconcile(credentials(context)) }
        schedule(context)
    }

    fun token(context: Context, token: String) {
        transaction(context) { it.token(token); it.reconcile(credentials(context)) }
        schedule(context)
    }

    fun credentialsChanged(context: Context) {
        transaction(context) { it.reconcile(credentials(context)) }
        schedule(context)
    }

    fun unregister(context: Context) {
        transaction(context) { it.unregister() }
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    /** No rescheduling for duplicate configuration/token/credentials. */
    fun schedule(context: Context, force: Boolean = false): TokenScheduleResult = synchronized(lock) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: error("JobScheduler unavailable")
        val pending = if (allowed(context)) transaction(context) { state ->
            state.reconcile(credentials(context))
            state.due()?.let { state.generation to it }
        } else null
        // Let the current serialized HTTP request finish before sending a new
        // generation, even if its last permitted attempt is already reserved.
        // Consent revocation still cancels immediately; unregister cancels too.
        if (active != null && allowed(context)) return@synchronized TokenScheduleResult.UNCHANGED
        if (pending == null) {
            scheduler.cancel(JOB_ID)
            return@synchronized TokenScheduleResult.EMPTY
        }
        val (generation, due) = pending
        val key = "$generation:$due"
        if (!force && scheduler.getPendingJob(JOB_ID)?.extras?.getString("revision") == key) return@synchronized TokenScheduleResult.UNCHANGED
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, PushTokenRegistrationJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setMinimumLatency((due - System.currentTimeMillis()).coerceAtLeast(0))
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString("revision", key)
                putLong("invocation", SystemClock.elapsedRealtimeNanos())
            })
            .build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Token registration job could not be scheduled" }
        TokenScheduleResult.SCHEDULED
    }

    fun pending(context: Context): Boolean = allowed(context) && transaction(context) {
        it.reconcile(credentials(context)); it.due() != null
    }

    internal class Cancellation {
        val stopped = AtomicBoolean(false)
        private val connection = AtomicReference<HttpURLConnection?>()
        fun observe(value: HttpURLConnection?) {
            connection.set(value)
            if (stopped.get()) value?.disconnect()
        }
        fun stop() {
            stopped.set(true)
            runCatching { connection.getAndSet(null)?.disconnect() }
        }
    }

    /**
     * onStopJob may have observed an exhausted eighth reservation before the
     * worker found it had not opened the network. Return the attempt and its
     * wake-up together; scheduling still checks consent, accounts and active work.
     */
    internal fun releaseReservation(context: Context, attempt: TokenRegistrationAttempt) {
        val restored = transaction(context) { it.release(attempt, credentials(context)) }
        if (!restored) return
        // A stopped execution cannot call jobFinished again. Recover its job
        // directly; if the platform refuses, durable state also recovers at the
        // next process boot/foreground/event, without a polling loop.
        runCatching { schedule(context) }
    }

    fun drain(context: Context, cancellation: Cancellation) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        repeat(8) {
            if (cancellation.stopped.get() || Thread.currentThread().isInterrupted || !allowed(context) || SystemClock.elapsedRealtime() >= deadline) return
            val attempt = transaction(context) { it.begin(System.currentTimeMillis(), credentials(context)) } ?: return
            val current = {
                !cancellation.stopped.get() && !Thread.currentThread().isInterrupted && allowed(context) &&
                    transaction(context) { it.current(attempt, credentials(context)) }
            }
            if (!current()) {
                releaseReservation(context, attempt)
                return
            }
            var submitted = false
            val status = try {
                NotificationEndpoint.sendForResult(context, JSONObject(attempt.endpoint),
                    mapOf("token" to attempt.token, "account" to attempt.account),
                    credentials = { account -> if (PamNotificationCredentials.normalize(account) == attempt.account) attempt.credential else null },
                    beforeSend = { current().also { if (it) submitted = true } }, connectionObserver = cancellation::observe).status
            } catch (_: IOException) {
                0
            } catch (_: Exception) {
                // Invalid configuration is terminal; it is not an offline retry.
                400
            }
            if (cancellation.stopped.get() || !allowed(context)) {
                if (!submitted) releaseReservation(context, attempt)
                return
            }
            // Check the generation and actual Keystore credential again before ack.
            transaction(context) { it.finish(attempt, status, credentials(context)) }
        }
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key -> JSONObject.quote(key) + ":" + canonical(value.get(key)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}

/** Token events are explicit, app-local broadcasts from the core Firebase service. */
class PushTokenRegistrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundPush.ACTION_TOKEN_REFRESHED) return
        val token = intent.getStringExtra(BackgroundPush.EXTRA_TOKEN)?.takeIf {
            it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 8_192
        } ?: return
        val pending = goAsync()
        try { PamPushTokenRegistration.receive(context.applicationContext, token, finished = pending::finish) }
        catch (_: Exception) { pending.finish() }
    }
}

/** Android owns offline waiting. A stopped job disconnects I/O before any later request. */
class PushTokenRegistrationJob : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private var cancellation: PamPushTokenRegistration.Cancellation? = null
    private var future: FutureTask<Unit>? = null
    private var invocation: Long? = null

    override fun onStartJob(params: JobParameters): Boolean {
        cancellation?.let { it.stop(); PamPushTokenRegistration.stopped(it) }
        PamPushTokenRegistration.cancelWorker(future)
        invocation = params.extras.getLong("invocation")
        val cancel = PamPushTokenRegistration.Cancellation()
        cancellation = cancel
        PamPushTokenRegistration.started(cancel)
        val task = FutureTask<Unit> {
            try { PamPushTokenRegistration.drain(applicationContext, cancel) }
            finally {
                main.post {
                    if (cancellation === cancel && !cancel.stopped.get()) {
                        cancellation = null
                        future = null
                        PamPushTokenRegistration.stopped(cancel)
                        // Establish the next wake-up before releasing this one.
                        // If scheduling fails, Android retains/retries this job.
                        invocation = null
                        val result = runCatching { PamPushTokenRegistration.schedule(applicationContext, force = true) }
                        if (result.getOrNull() != TokenScheduleResult.SCHEDULED) {
                            // Scheduling the same ID replaces this execution:
                            // Android says not to finish the replaced job again.
                            val retry = result.isFailure && runCatching {
                                PamPushTokenRegistration.pending(applicationContext)
                            }.getOrDefault(true)
                            jobFinished(params, retry)
                        }
                    }
                }
            }
        }
        future = task
        return try { PamPushTokenRegistration.worker.execute(task); true }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            PamPushTokenRegistration.stopped(cancel)
            future = null
            // Return true, then finish asynchronously with retry. A transient
            // full executor must not discard a persisted job's only wake-up.
            main.post {
                if (cancellation === cancel && !cancel.stopped.get()) {
                    cancellation = null
                    invocation = null
                    jobFinished(params, runCatching { PamPushTokenRegistration.pending(applicationContext) }.getOrDefault(true))
                }
            }
            true
        }
    }

    override fun onDestroy() {
        cancellation?.let { it.stop(); PamPushTokenRegistration.stopped(it) }
        PamPushTokenRegistration.cancelWorker(future)
        cancellation = null
        future = null
        invocation = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (invocation != params.extras.getLong("invocation")) return false
        invocation = null
        cancellation?.let { it.stop(); PamPushTokenRegistration.stopped(it) }
        PamPushTokenRegistration.cancelWorker(future)
        cancellation = null
        future = null
        return runCatching { PamPushTokenRegistration.pending(applicationContext) }.getOrDefault(true)
    }
}
