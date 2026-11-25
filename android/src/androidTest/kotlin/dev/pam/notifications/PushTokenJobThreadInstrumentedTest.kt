package dev.pam.notifications

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushTokenJobThreadInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)

    private fun field(name: String): Any = PamPushTokenRegistration::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(PamPushTokenRegistration)!!

    @Suppress("UNCHECKED_CAST")
    private fun active() = field("active") as AtomicReference<PamPushTokenRegistration.Cancellation?>

    @Test fun mainLifecycleOwnershipDoesNotWaitForPersistenceOrClearNewExecution() {
        val first = PamPushTokenRegistration.Cancellation()
        val next = PamPushTokenRegistration.Cancellation()
        val completed = CountDownLatch(1)
        val retained = AtomicReference<Boolean>()
        try {
            withPersistenceBusy {
                Handler(Looper.getMainLooper()).post {
                    PamPushTokenRegistration.started(first)
                    PamPushTokenRegistration.started(next)
                    PamPushTokenRegistration.stopped(first)
                    retained.set(active().get() === next)
                    PamPushTokenRegistration.stopped(next)
                    completed.countDown()
                }
                assertTrue("Main waited for token persistence", completed.await(2, TimeUnit.SECONDS))
                assertTrue("Old completion cleared the newer execution", retained.get())
                assertNull(active().get())
            }
        } finally {
            completed.await(2, TimeUnit.SECONDS)
            PamPushTokenRegistration.stopped(first)
            PamPushTokenRegistration.stopped(next)
        }
    }

    @Test fun realJobStartAndStopKeepMainResponsiveWhilePersistenceIsBusy() {
        // Only this instrumentation target's isolated preferences/job are used.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        PamPushTokenRegistration.unregister(context)
        val invocation = SystemClock.elapsedRealtimeNanos()
        val job = JobInfo.Builder(PamPushTokenRegistration.JOB_ID,
            ComponentName(context, PushTokenRegistrationJob::class.java))
            .setMinimumLatency(60_000)
            .setExtras(PersistableBundle().apply { putLong("invocation", invocation) })
            .build()
        try {
            withPersistenceBusy {
                assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
                shell("cmd jobscheduler run -f ${context.packageName} ${PamPushTokenRegistration.JOB_ID}")
                await("Real onStartJob waited for the state lock") { active().get() != null }
                val running = requireNotNull(active().get())
                shell("cmd jobscheduler timeout ${context.packageName} ${PamPushTokenRegistration.JOB_ID}")
                await("Real onStopJob waited for the state lock") { running.stopped.get() && active().get() == null }
                val heartbeat = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                assertTrue("onStopJob blocked subsequent main work", heartbeat.await(1, TimeUnit.SECONDS))
            }
            // The stopped worker must unwind before the fixture is removed.
            val drained = java.util.concurrent.FutureTask<Unit> { Unit }
            PamPushTokenRegistration.worker.execute(drained)
            drained.get(2, TimeUnit.SECONDS)
        } finally {
            scheduler.cancel(PamPushTokenRegistration.JOB_ID)
            PamPushTokenRegistration.unregister(context)
        }
    }

    private fun withPersistenceBusy(work: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(isDaemon = true, name = "token-job-state-lock-test") {
            synchronized(field("lock")) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            work()
        } finally {
            release.countDown()
            holder.join(2_000)
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(message, condition())
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                val output = reader.lineSequence().take(8).joinToString("\n")
                assertFalse("JobScheduler rejected the test command", output.contains("Error", ignoreCase = true))
            }
        }
    }
}
