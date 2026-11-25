package dev.pam.notifications

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleCompletion
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushTokenIntakeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    // Control existing synchronization only; production has no test hooks.
    private fun field(name: String): Any = PamPushTokenRegistration::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(PamPushTokenRegistration)!!

    @Test fun registerAndReceiverIntakeReturnOnMainWhilePersistenceIsBusy() {
        PamPushTokenRegistration.unregister(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, HarnessLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val module = NotificationsModule(activity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registered = CountDownLatch(1)
        val received = CountDownLatch(1)
        val stored = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val holder = thread(isDaemon = true, name = "token-intake-test-lock") {
            synchronized(field("lock")) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post {
                module.invoke("registerPush", ByteArray(0), ModuleCompletion { _, _ -> completed.countDown() })
                registered.countDown()
            }
            val registerReturned = registered.await(1, TimeUnit.SECONDS)
            Handler(Looper.getMainLooper()).post {
                // Exactly the receiver path: no persisted epoch supplied.
                PamPushTokenRegistration.receive(context, "public-intake-test-token") { stored.countDown() }
                received.countDown()
            }
            val receiveReturned = received.await(1, TimeUnit.SECONDS)
            assertTrue("registerPush blocked the main thread on persistence", registerReturned)
            assertTrue("Token receiver intake blocked the main thread on persistence", receiveReturned)
        } finally {
            release.countDown()
            holder.join(2_000)
            registered.await(2, TimeUnit.SECONDS)
            received.await(2, TimeUnit.SECONDS)
            stored.await(2, TimeUnit.SECONDS)
            completed.await(2, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { module.retireSurface(); activity.finish() }
            PamPushTokenRegistration.unregister(context)
        }
    }

    @Test fun logoutDiscardsQueuedReceiverAndDelayedFirebaseCallback() {
        PamPushTokenRegistration.unregister(context)
        val oldTicket = PamPushTokenRegistration.registrationTicket(context)
        val intake = field("intake") as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val blocker = FutureTask<Unit> { entered.countDown(); release.await(10, TimeUnit.SECONDS); Unit }
        try {
            intake.execute(blocker)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            PamPushTokenRegistration.receive(context, "queued-before-logout") { finished.countDown() }
            PamPushTokenRegistration.unregister(context)
            release.countDown()
            assertTrue("Queued receipt did not finish", finished.await(2, TimeUnit.SECONDS))
            assertTrue("Logout resurrected the queued receiver token", state().isNull("token"))

            // Callback arrives after logout with the ticket captured before
            // Firebase getToken; intake must reject it without touching disk.
            val callbackFinished = CountDownLatch(1)
            PamPushTokenRegistration.receive(context, "callback-after-logout", oldTicket) { callbackFinished.countDown() }
            assertTrue(callbackFinished.await(2, TimeUnit.SECONDS))
            assertTrue("Logout accepted an old Firebase callback", state().isNull("token"))
            assertEquals(0, state().getJSONArray("accounts").length())

            val freshFinished = CountDownLatch(1)
            PamPushTokenRegistration.receive(context, "fresh-receipt-after-logout") { freshFinished.countDown() }
            assertTrue(freshFinished.await(2, TimeUnit.SECONDS))
            assertEquals("fresh-receipt-after-logout", state().getString("token"))
            assertFalse("A token without configured accounts must not start work", PamPushTokenRegistration.pending(context))
        } finally {
            release.countDown()
            blocker.get(2, TimeUnit.SECONDS)
            finished.await(2, TimeUnit.SECONDS)
            PamPushTokenRegistration.unregister(context)
        }
    }

    @Test fun oldFirebaseCallbackCannotEvictFreshBroadcastQueuedAfterLogout() {
        PamPushTokenRegistration.unregister(context)
        val oldTicket = PamPushTokenRegistration.registrationTicket(context)
        val intake = field("intake") as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freshFinished = CountDownLatch(1)
        val staleFinished = CountDownLatch(1)
        val blocker = FutureTask<Unit> { entered.countDown(); release.await(10, TimeUnit.SECONDS); Unit }
        try {
            intake.execute(blocker)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            PamPushTokenRegistration.unregister(context)
            PamPushTokenRegistration.receive(context, "fresh-broadcast-after-logout") { freshFinished.countDown() }
            PamPushTokenRegistration.receive(context, "old-firebase-callback", oldTicket) { staleFinished.countDown() }
            assertTrue("Stale callback must finish without entering the queue", staleFinished.await(1, TimeUnit.SECONDS))
            assertEquals("Stale callback evicted the fresh pending receipt", 1L, freshFinished.count)
            release.countDown()
            assertTrue(freshFinished.await(2, TimeUnit.SECONDS))
            assertEquals("fresh-broadcast-after-logout", state().getString("token"))
            assertFalse(PamPushTokenRegistration.pending(context))
        } finally {
            release.countDown()
            blocker.get(2, TimeUnit.SECONDS)
            freshFinished.await(2, TimeUnit.SECONDS)
            PamPushTokenRegistration.unregister(context)
        }
    }

    private fun state() = JSONObject(context.getSharedPreferences("pam-native-push-token-registration", Context.MODE_PRIVATE)
        .getString("state", "{}") ?: "{}")
}
