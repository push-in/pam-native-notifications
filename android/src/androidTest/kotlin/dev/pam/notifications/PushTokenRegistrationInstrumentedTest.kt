package dev.pam.notifications

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushTokenRegistrationInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun grantConsent() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun endpoint(port: Int) = JSONObject().put("method", "POST")
        .put("url", "http://127.0.0.1:$port/register")
        .put("headers", JSONObject().put("Authorization", "Bearer {credential:account}"))
        .put("body", JSONObject().put("token", "{token}").put("account", "{account}").put("provider_type", 2)).toString()

    @Test fun headlessDeliverySerializesRotationAndDoesNotRepeatSuccessfulConfiguration() {
        grantConsent()
        val cancel = PamPushTokenRegistration.Cancellation()
        PamPushTokenRegistration.unregister(context)
        PamPushTokenRegistration.started(cancel) // Exercise worker directly, keep OS scheduling out of the test.
        val oldRequest = CountDownLatch(1)
        val allowOldResponse = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val requests = mutableListOf<Pair<String, String>>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val serving = thread(isDaemon = true, name = "token-registration-test-server") {
                try {
                    repeat(2) { index -> server.accept().use { socket ->
                        socket.soTimeout = 8_000
                        val reader = socket.getInputStream().bufferedReader()
                        val header = StringBuilder()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: error("Missing request")
                            if (line.isEmpty()) break
                            header.append(line).append('\n')
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        require(length in 1..16_384)
                        val body = CharArray(length)
                        var read = 0
                        while (read < length) { val got = reader.read(body, read, length - read); require(got > 0); read += got }
                        synchronized(requests) { requests += header.toString() to String(body) }
                        if (index == 0) { oldRequest.countDown(); check(allowOldResponse.await(5, TimeUnit.SECONDS)) }
                        socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } }
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                PamNotificationCredentials.replace(context, mapOf("account" to "session-old"))
                PamPushTokenRegistration.configure(context, endpoint(server.localPort), "[\"account\"]")
                PamPushTokenRegistration.token(context, "fcm-old")
                thread(isDaemon = true, name = "token-registration-test-worker") {
                    try { PamPushTokenRegistration.drain(context, cancel) }
                    catch (error: Throwable) { failure.set(error) }
                    finally { finished.countDown() }
                }
                val received = oldRequest.await(5, TimeUnit.SECONDS)
                assertTrue("No loopback request: bind=${server.inetAddress.hostAddress}:${server.localPort}, " +
                    "fixture=${failure.get()?.javaClass?.simpleName ?: "none"}, workerFinished=${finished.count == 0L}, ${queueDiagnostic()}", received)
                PamNotificationCredentials.set(context, "account", "session-new")
                PamPushTokenRegistration.token(context, "fcm-new")
                assertEquals(1, synchronized(requests) { requests.size })
                allowOldResponse.countDown()
                val delivered = finished.await(8, TimeUnit.SECONDS)
                assertTrue("Worker did not finish: fixture=${failure.get()?.javaClass?.simpleName ?: "none"}, " +
                    "requests=${synchronized(requests) { requests.size }}, ${queueDiagnostic()}", delivered)
                serving.join(1_000)
                failure.get()?.let { throw AssertionError("Loopback delivery failed", it) }
                assertEquals(2, requests.size)
                assertTrue(requests[0].first.contains("Bearer session-old"))
                assertTrue(requests[1].first.contains("Bearer session-new"))
                assertEquals("fcm-new", JSONObject(requests[1].second).getString("token"))
                PamPushTokenRegistration.configure(context, endpoint(server.localPort), "[\"account\"]")
                PamPushTokenRegistration.token(context, "fcm-new")
                assertFalse(PamPushTokenRegistration.pending(context))
                PamPushTokenRegistration.drain(context, cancel)
                assertEquals(2, requests.size)
            } finally {
                allowOldResponse.countDown()
                cancel.stop()
                finished.await(2, TimeUnit.SECONDS)
                PamPushTokenRegistration.unregister(context)
                PamPushTokenRegistration.stopped(cancel)
                PamNotificationCredentials.clear(context)
            }
        }
    }

    @android.annotation.SuppressLint("UseKtx") // Check the fixture's durable commit result explicitly.
    @Test fun stoppedEighthReservationRestoresItsRealJobWhenReleasedBeforeNetwork() {
        grantConsent()
        PamPushTokenRegistration.unregister(context)
        PamNotificationCredentials.replace(context, mapOf("account" to "test-credential"))
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val releaseServer = CountDownLatch(1)
        val peer = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
        val serving = thread(isDaemon = true, name = "token-reservation-test-server") {
            try {
                server.soTimeout = 5_000
                server.accept().use { socket ->
                    peer.set(socket)
                    // If Android starts the restored job immediately, keep it
                    // observable until the assertion and cleanup finish.
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
            } catch (_: java.io.IOException) { /* Owned fixture closes on cleanup. */ }
        }
        try {
            val lookup: (String) -> String? = { PamNotificationCredentials.token(context, it) }
            val state = TokenRegistrationState().apply {
                configure(endpoint(server.localPort), listOf("account"))
                token("test-fcm")
                reconcile(lookup)
            }
            var now = 0L
            repeat(7) { index ->
                state.finish(requireNotNull(state.begin(now, lookup)), 503, lookup)
                now += TokenRegistrationState.backoff(index + 1)
            }
            val eighth = requireNotNull(state.begin(now, lookup))
            assertNull(state.due()) // Reservation 8 is provisionally exhausted.
            val prefs = context.getSharedPreferences("pam-native-push-token-registration", Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString("state", state.json()).commit())
            // Exact failing ordering: onStop decides no retry before the worker
            // returns its unsent reservation. No job is available to rescue it.
            assertFalse(PamPushTokenRegistration.pending(context))
            scheduler.cancel(PamPushTokenRegistration.JOB_ID)
            assertNull(scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID))
            PamPushTokenRegistration.releaseReservation(context, eighth)
            val restored = scheduler.getPendingJob(PamPushTokenRegistration.JOB_ID)
            assertNotNull("An unsent eighth reservation became PENDING without a wake-up", restored)
            assertTrue(requireNotNull(restored).isPersisted)
        } finally {
            PamPushTokenRegistration.unregister(context)
            PamNotificationCredentials.clear(context)
            releaseServer.countDown()
            runCatching { peer.get()?.close() }
            server.close()
            serving.join(1_000)
        }
    }

    private fun queueDiagnostic(): String {
        val state = JSONObject(context.getSharedPreferences("pam-native-push-token-registration", Context.MODE_PRIVATE)
            .getString("state", "{}") ?: "{}")
        val delivery = state.optJSONObject("deliveries")?.optJSONObject("account")
        return "deliveryState=${delivery?.optInt("state")}, attempts=${delivery?.optInt("attempts")}, " +
            "consent=${androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()}"
    }

    @Test fun cancelledQueuedWorkerDoesNotBlockTheNextGeneration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicInteger()
        val blocking = FutureTask<Unit> { entered.countDown(); release.await(5, TimeUnit.SECONDS); Unit }
        val cancelled = FutureTask<Unit> { executed.incrementAndGet(); Unit }
        val next = FutureTask<Unit> { executed.addAndGet(10); Unit }
        try {
            PamPushTokenRegistration.worker.execute(blocking)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            PamPushTokenRegistration.worker.execute(cancelled)
            PamPushTokenRegistration.cancelWorker(cancelled)
            assertTrue(cancelled.isCancelled)
            PamPushTokenRegistration.worker.execute(next)
            release.countDown()
            next.get(2, TimeUnit.SECONDS)
            assertEquals(10, executed.get())
        } finally {
            release.countDown()
            PamPushTokenRegistration.cancelWorker(blocking)
            PamPushTokenRegistration.cancelWorker(cancelled)
            PamPushTokenRegistration.cancelWorker(next)
        }
    }
}
