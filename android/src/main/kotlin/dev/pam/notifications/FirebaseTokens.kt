package dev.pam.notifications

import android.os.Handler
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Firebase Messaging is optional for this plugin: the host app adds it only
 * when it ships a google-services.json, and plugin modules cannot declare
 * compile-only dependencies. The calls therefore stay reflective, and
 * android/consumer-rules.pro keeps every class and member named here so R8
 * cannot rename or strip them in release builds.
 */
internal object FirebaseTokens {
    private const val MESSAGING = "com.google.firebase.messaging.FirebaseMessaging"
    private const val TASK = "com.google.android.gms.tasks.Task"
    private const val TIMEOUT_MS = 15_000L
    private const val POLL_MS = 100L

    /** The reflective view of a com.google.android.gms.tasks.Task. */
    class TaskView(private val task: Any, private val methods: TaskMethods) {
        val complete: Boolean get() = methods.isComplete.call(task) as Boolean
        val successful: Boolean get() = methods.isSuccessful.call(task) as Boolean
        val result: Any? get() = methods.getResult.call(task)
        val exception: Throwable? get() = methods.getException.call(task) as? Throwable
    }

    class TaskMethods(
        val isComplete: Method,
        val isSuccessful: Method,
        val getResult: Method,
        val getException: Method,
    )

    private val taskMethods: TaskMethods by lazy {
        val task = Class.forName(TASK)
        TaskMethods(
            task.method("isComplete"),
            task.method("isSuccessful"),
            task.method("getResult"),
            task.method("getException"),
        )
    }

    /**
     * Starts FirebaseMessaging.[operation] (getToken or deleteToken) and
     * completes [completion] with [success] once its Task settles, or with a
     * FAILURE message that names the cause (missing Firebase, a stripped
     * member, Firebase's own exception, or the timeout).
     */
    fun await(
        main: Handler,
        operation: String,
        label: String,
        completion: ModuleCompletion,
        success: (TaskView) -> ByteArray,
    ) {
        val task = runCatching {
            val messaging = runCatching { Class.forName(MESSAGING) }.getOrElse {
                error("$label requires the host app to provide Firebase Messaging")
            }
            val instance = messaging.method("getInstance").call(null)
            TaskView(
                requireNotNull(messaging.method(operation).call(instance)) { "Firebase $operation returned no task" },
                taskMethods,
            )
        }.getOrElse { error ->
            completion.complete(ModuleResultStatus.FAILURE, describe(label, error).toByteArray())
            return
        }
        val started = System.currentTimeMillis()
        fun poll() {
            runCatching {
                if (!task.complete) {
                    require(System.currentTimeMillis() - started < TIMEOUT_MS) { "$label timed out" }
                    main.postDelayed(::poll, POLL_MS)
                    return
                }
                if (!task.successful) {
                    val cause = task.exception
                    error("Firebase rejected ${label.lowercase()}" + (cause?.let { ": ${it.javaClass.name}: ${it.message}" } ?: ""))
                }
                success(task)
            }.fold(
                onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                onFailure = { completion.complete(ModuleResultStatus.FAILURE, describe(label, it).toByteArray()) },
            )
        }
        main.post(::poll)
    }

    private fun Class<*>.method(name: String): Method = try {
        getMethod(name)
    } catch (missing: NoSuchMethodException) {
        throw IllegalStateException("$name() is missing from ${this.name}; R8 stripped it or Firebase changed its API", missing)
    }

    private fun Method.call(target: Any?): Any? = try {
        invoke(target)
    } catch (error: InvocationTargetException) {
        throw error.targetException ?: error
    }

    private fun describe(label: String, error: Throwable): String {
        val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.name
        return if (message.startsWith(label) || message.startsWith("Firebase")) message else "$label failed: $message"
    }
}
