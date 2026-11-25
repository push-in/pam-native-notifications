package dev.pam.notifications

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.push.BackgroundPush
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real manifest receiver, persisted scheduler and JobService, never drain(). */
@RunWith(AndroidJUnit4::class)
class PushTokenJobPipelineInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private var fixture: SequenceServer? = null

    @Test fun transientResponseLeavesARealNextJobThenSuccessDeduplicatesBroadcasts() {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        PamPushTokenRegistration.unregister(context)
        PamNotificationCredentials.clear(context)
        SequenceServer(listOf(503, 201)).use { server ->
            fixture = server
            val endpoint = JSONObject().put("method", "POST")
                .put("url", "http://127.0.0.1:${server.port}/token")
                .put("headers", JSONObject().put("Authorization", "Bearer {credential:account}"))
                .put("body", JSONObject().put("token", "{token}").put("account", "{account}")).toString()
            try {
                PamNotificationCredentials.set(context, "account", "test-bearer")
                PamPushTokenRegistration.configure(context, endpoint, "[\"account\"]")
                tokenBroadcast("test-fcm")
                await(deadline, "Receiver did not persist token and schedule job") {
                    state().optString("token") == "test-fcm" && scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID) != null
                }
                if (server.requests.get() == 0) forceScheduledJob()
                await(deadline, "First JobService did not reach HTTP") { server.requests.get() == 1 }
                // The state was persisted before the network attempt. A true
                // replacement must now expose the RETRY deadline in its extras;
                // merely seeing the old executing job is insufficient evidence.
                await(deadline, "503 lost the next real scheduled job") {
                    val snapshot = state()
                    val delivery = snapshot.optJSONObject("deliveries")?.optJSONObject("account")
                    val next = scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID)
                    delivery != null && next != null && delivery.optInt("attempts") == 1 &&
                        delivery.optInt("state") == TokenDeliveryState.PENDING.wire &&
                        next.extras.getString("revision") == "${snapshot.optLong("generation")}:${delivery.optLong("after")}" && next.isPersisted
                }
                val due = state().getJSONObject("deliveries").getJSONObject("account").getLong("after")
                while (System.currentTimeMillis() <= due && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep((due - System.currentTimeMillis() + 25).coerceIn(1, 250))
                }
                assertTrue("Retry deadline exceeded test budget", SystemClock.elapsedRealtime() < deadline)
                if (server.requests.get() == 1) forceScheduledJob()
                await(deadline, "Retry JobService did not acknowledge HTTP201") {
                    server.requests.get() == 2 && state().optJSONObject("deliveries")?.optJSONObject("account")
                        ?.optInt("state") == TokenDeliveryState.ACKNOWLEDGED.wire
                }
                await(deadline, "Completed delivery left a live job") { scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID) == null }
                val completed = state().getJSONObject("deliveries").getJSONObject("account").toString()
                repeat(3) {
                    PamPushTokenRegistration.configure(context, endpoint, "[\"account\"]")
                    PamNotificationCredentials.set(context, "account", "test-bearer")
                    tokenBroadcast("test-fcm")
                }
                Thread.sleep(500)
                assertEquals(2, server.requests.get())
                assertNull(scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID))
                assertEquals(completed, state().getJSONObject("deliveries").getJSONObject("account").toString())
                server.failure.get()?.let { throw AssertionError("Loopback server failed", it) }
            } finally {
                PamPushTokenRegistration.unregister(context)
                PamNotificationCredentials.clear(context)
            }
        }
    }

    private fun tokenBroadcast(token: String) {
        context.sendBroadcast(Intent(context, PushTokenRegistrationReceiver::class.java)
            .setAction(BackgroundPush.ACTION_TOKEN_REFRESHED).putExtra(BackgroundPush.EXTRA_TOKEN, token))
    }

    private fun state() = JSONObject(context.getSharedPreferences("pam-native-push-token-registration", Context.MODE_PRIVATE)
        .getString("state", "{}") ?: "{}")

    private fun await(deadline: Long, message: String, predicate: () -> Boolean) {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        val delivery = state().optJSONObject("deliveries")?.optJSONObject("account")
        fail("$message; ${fixture?.diagnostic()}, deliveryState=${delivery?.optInt("state")}, " +
            "attempts=${delivery?.optInt("attempts")}, pendingJob=${scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID) != null}, " +
            "consent=${androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()}")
    }

    private fun forceScheduledJob() {
        assertNotNull("No persisted job exists to run", scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID))
        val command = "cmd jobscheduler run -f ${context.packageName} ${PamPushTokenRegistration.JOB_ID}"
        val output = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.lineSequence().take(8).joinToString("\n") }
        }
        assertTrue("Scheduler command failed: $output", output.contains("Running job", true) || output.contains("already", true))
    }

    private class SequenceServer(private val statuses: List<Int>) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val worker = thread(isDaemon = true, name = "token-job-loopback") {
            try {
                server.soTimeout = 45_000
                statuses.forEach { status -> server.accept().use { socket ->
                    sockets += socket
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: error("Incomplete HTTP request")
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..16_384)
                    val body = CharArray(length)
                    var count = 0
                    while (count < length) { val read = reader.read(body, count, length - count); require(read > 0); count += read }
                    val parsed = JSONObject(String(body))
                    check(parsed.getString("token") == "test-fcm" && parsed.getString("account") == "account")
                    socket.getOutputStream().write("HTTP/1.1 $status Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    requests.incrementAndGet()
                    sockets.remove(socket)
                } }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }
        fun diagnostic(): String = "bind=${server.inetAddress.hostAddress}:${server.localPort}, " +
            "requests=${requests.get()}, fixtureFailure=${failure.get()?.javaClass?.simpleName ?: "none"}"

        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            worker.join(1_000)
        }
    }
}
