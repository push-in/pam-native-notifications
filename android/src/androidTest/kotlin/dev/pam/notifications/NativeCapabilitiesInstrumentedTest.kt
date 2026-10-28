package dev.pam.notifications

import dev.pam.nativeapp.modules.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.CapabilityTestActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCapabilitiesInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test
    fun conversationNotificationsUseMessagingStyleRepliesAndHistory() {
        val key = "chat:${UUID.randomUUID()}"
        val first = ConversationSpec(
            key = key,
            title = "Viagem",
            group = true,
            self = ConversationPerson("Você"),
            messages = listOf(ConversationMessage("m1", "Partiu?", 1_000, ConversationPerson("Ana", key = "ana"))),
            replyLabel = "Responder",
            markReadLabel = "Marcar como lida",
        )
        try {
            PamConversationNotifications.show(context, first)
            val merged = PamConversationNotifications.show(
                context,
                first.copy(messages = listOf(
                    ConversationMessage("m1", "Partiu?", 1_000, ConversationPerson("Ana", key = "ana")),
                    ConversationMessage("m2", "Bora!", 2_000, ConversationPerson("Bia", key = "bia")),
                )),
            )
            assertEquals(listOf("m1", "m2"), merged.messages.map { it.id })
            val notification = PamConversationNotifications.build(context, merged).build()
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            assertNotNull(style)
            assertEquals("Viagem", style!!.conversationTitle)
            assertTrue(style.isGroupConversation)
            assertEquals(listOf("Partiu?", "Bora!"), style.messages.map { it.text.toString() })
            val reply = NotificationCompat.getAction(notification, 0)
            assertEquals("Responder", reply?.title)
            assertEquals(PamConversationNotifications.REMOTE_INPUT_KEY, reply?.remoteInputs?.single()?.resultKey)
            assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, NotificationCompat.getAction(notification, 1)?.semanticAction)
            val updated = PamConversationNotifications.appendOwnReply(context, key, "Já vou")
            assertNull(updated?.messages?.last()?.sender)
            assertEquals("Já vou", updated?.messages?.last()?.text)
        } finally {
            PamConversationNotifications.cancel(context, key)
        }
        assertNull(PamConversationNotifications.load(context, key))
    }

    @Test
    fun inlineReplyIsSentNativelyAndQueuedForPhp() {
        val key = "chat-${UUID.randomUUID()}"
        context.getSharedPreferences("pam-native", Context.MODE_PRIVATE).edit().putString("auth.token", "secret").commit()
        LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
            PamConversationNotifications.show(
                context,
                ConversationSpec(
                    key = key,
                    messages = listOf(ConversationMessage("m1", "Oi", 1_000, ConversationPerson("Ana"))),
                    replyLabel = "Responder",
                    replyEndpoint = JSONObject()
                        .put("method", "POST")
                        .put("url", "http://127.0.0.1:${server.port}/chats/{chat_id}/messages")
                        .put("headers", JSONObject().put("Authorization", "Bearer {storage:auth.token}"))
                        .put("body", JSONObject().put("body", "{reply}").put("conversation", "{conversation}")),
                    dataJson = """{"chat_id":"42"}""",
                ),
            )
            val intent = Intent(context, PamNotificationActionReceiver::class.java)
                .setAction(PamConversationNotifications.ACTION_REPLY)
                .putExtra(PamConversationNotifications.EXTRA_KEY, key)
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(PamConversationNotifications.REMOTE_INPUT_KEY).build()),
                intent,
                android.os.Bundle().apply { putCharSequence(PamConversationNotifications.REMOTE_INPUT_KEY, "Já vou") },
            )
            PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Já vou")
            val request = server.request()
            assertTrue(request.head.startsWith("POST /chats/42/messages HTTP/1.1"))
            assertTrue(request.head.contains("Authorization: Bearer secret"))
            assertEquals("Já vou", JSONObject(String(request.body, Charsets.UTF_8)).getString("body"))
            val event = AtomicReference<Map<String, WireValue>>()
            val latch = CountDownLatch(1)
            var drained = false
            while (!drained) {
                val next = CountDownLatch(1)
                PamNotificationActions.next { _, payload ->
                    val values = WireMap.decode(payload)
                    if ((values["conversation"] as? WireValue.Text)?.value == key) {
                        event.set(values)
                        latch.countDown()
                        drained = true
                    }
                    next.countDown()
                }
                assertTrue(next.await(5, TimeUnit.SECONDS))
            }
            assertTrue(latch.await(1, TimeUnit.SECONDS))
            assertEquals(WireValue.Text("Já vou"), event.get()["text"])
            assertEquals(WireValue.Integer(201), event.get()["statusCode"])
            assertEquals(WireValue.Flag(true), event.get()["handledNatively"])
        }
        PamConversationNotifications.cancel(context, key)
    }

    @Test
    fun notificationCredentialsAreKeystoreSealedPerAccount() {
        PamNotificationCredentials.clear(context)
        PamNotificationCredentials.set(context, " User-A ", "token-a")
        PamNotificationCredentials.set(context, "user-b", "token-b")
        val raw = context.getSharedPreferences("pam-native-notification-credentials", Context.MODE_PRIVATE).all
        assertEquals(2, raw.size)
        raw.forEach { (slot, value) ->
            assertFalse(slot.contains("user"))
            assertFalse((value as String).contains("token-"))
        }
        assertEquals("token-a", PamNotificationCredentials.token(context, "USER-A"))
        assertEquals("token-b", PamNotificationCredentials.token(context, "user-b"))
        PamNotificationCredentials.remove(context, "user-a")
        assertNull(PamNotificationCredentials.token(context, "user-a"))
        PamNotificationCredentials.replace(context, mapOf("user-c" to "token-c"))
        assertNull(PamNotificationCredentials.token(context, "user-b"))
        assertEquals("token-c", PamNotificationCredentials.token(context, "user-c"))
        PamNotificationCredentials.clear(context)
        assertNull(PamNotificationCredentials.token(context, "user-c"))
    }

    @Test
    fun inlineReplyIsSentWithThePushAccountCredential() {
        val key = "chat-${UUID.randomUUID()}"
        PamNotificationCredentials.replace(context, mapOf("user-a" to "token-a", "user-b" to "token-b"))
        try {
            LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
                PamConversationNotifications.show(context, accountConversation(key, "http://127.0.0.1:${server.port}", "USER-B"))
                PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Já vou")
                val request = server.request()
                assertTrue(request.head.startsWith("POST /chats/42/messages HTTP/1.1"))
                assertTrue(request.head.contains("Authorization: Bearer token-b"))
                assertFalse(request.head.contains("token-a"))
                assertFalse(String(request.body, Charsets.UTF_8).contains("token-"))
                val event = drainAction(key)
                assertEquals(WireValue.Integer(201), event["statusCode"])
                assertEquals(WireValue.Flag(true), event["handledNatively"])
                assertEquals(WireValue.Flag(false), event["credentialMissing"])
                assertNotNull(PamConversationNotifications.load(context, key))
            }
        } finally {
            PamConversationNotifications.cancel(context, key)
            PamNotificationCredentials.clear(context)
        }
    }

    @Test
    fun actionOfAnAccountWithoutCredentialIsDismissedWithoutRequest() {
        val key = "chat-${UUID.randomUUID()}"
        PamNotificationCredentials.replace(context, mapOf("user-a" to "token-a"))
        try {
            LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
                PamConversationNotifications.show(context, accountConversation(key, "http://127.0.0.1:${server.port}", "user-b"))
                PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Não deve sair")
                val event = drainAction(key)
                assertEquals(WireValue.Flag(false), event["handledNatively"])
                assertEquals(WireValue.Flag(true), event["credentialMissing"])
                assertNull(PamConversationNotifications.load(context, key))
                assertNull(server.requestOrNull(500))
            }
        } finally {
            PamConversationNotifications.cancel(context, key)
            PamNotificationCredentials.clear(context)
        }
    }

    private fun accountConversation(key: String, base: String, account: String) = ConversationSpec(
        key = key,
        messages = listOf(ConversationMessage("m1", "Oi", 1_000, ConversationPerson("Ana"))),
        replyLabel = "Responder",
        markReadLabel = "Marcar como lida",
        replyEndpoint = JSONObject()
            .put("method", "POST")
            .put("url", "$base/chats/{chat_id}/messages")
            .put("headers", JSONObject().put("Authorization", "Bearer {credential:user_id|recipient_user_id}"))
            .put("body", JSONObject().put("body", "{reply}")),
        dataJson = """{"chat_id":"42","user_id":"$account"}""",
    )

    private fun drainAction(key: String): Map<String, WireValue> {
        val event = AtomicReference<Map<String, WireValue>>()
        while (event.get() == null) {
            val next = CountDownLatch(1)
            PamNotificationActions.next { _, payload ->
                val values = WireMap.decode(payload)
                if ((values["conversation"] as? WireValue.Text)?.value == key) event.set(values)
                next.countDown()
            }
            assertTrue(next.await(5, TimeUnit.SECONDS))
        }
        return event.get()
    }

    @Test
    fun pushRenderingRulesRenderSuppressAndDismissConversations() {
        val rule = JSONObject()
            .put("type", "chat.message")
            .put("field", "type")
            .put("kind", PamPushRendering.KIND_CONVERSATION)
            .put("conversation", JSONObject().put("key", "{chat_id}").put("title", "{chat_title}").put("group", "{is_group}"))
            .put("message", JSONObject().put("sender", "{sender_name}").put("text", "{body}").put("timestamp", "{sent_at}").put("id", "{message_id}"))
            .put("reply", JSONObject().put("label", "Responder"))
            .put("suppress", JSONArray().put(JSONObject().put("path", "chat/{chat_id}")))
        PamPushRendering.register(context, rule.toString())
        PamPushRendering.register(
            context,
            JSONObject().put("type", "chat.read").put("kind", PamPushRendering.KIND_DISMISS)
                .put("conversation", JSONObject().put("key", "{chat_id}")).toString(),
        )
        val chat = "c${UUID.randomUUID().toString().take(8)}"
        val data = JSONObject().put("type", "chat.message").put("chat_id", chat).put("chat_title", "Grupo")
            .put("is_group", "1").put("sender_name", "Ana").put("body", "Oi").put("sent_at", "1700000000").put("message_id", "m1")
        try {
            PamActiveRoute.setForeground(false)
            assertTrue(PamPushRendering.render(context, "p1", "", "", data.toString()))
            val stored = PamConversationNotifications.load(context, chat)
            assertNotNull(stored)
            assertTrue(stored!!.group)
            assertEquals(1_700_000_000_000, stored.messages.single().timestamp)

            PamActiveRoute.update("chat", """{"chatId":"$chat"}""", "/chat/$chat")
            PamActiveRoute.setForeground(true)
            assertTrue(PamPushRendering.render(context, "p2", "", "", data.put("message_id", "m2").toString()))
            assertEquals(1, PamConversationNotifications.load(context, chat)!!.messages.size)

            assertTrue(PamPushRendering.render(context, "p3", "", "", JSONObject().put("type", "chat.read").put("chat_id", chat).toString()))
            assertNull(PamConversationNotifications.load(context, chat))
            assertFalse(PamPushRendering.render(context, "p4", "", "", JSONObject().put("type", "other").toString()))
        } finally {
            PamActiveRoute.setForeground(false)
            PamActiveRoute.update("", "{}", "")
            PamPushRendering.clear(context)
            PamConversationNotifications.cancel(context, chat)
        }
    }

    private fun failureConversation(key: String, base: String, account: String = "user-a") = ConversationSpec(
        key = key,
        messages = listOf(ConversationMessage("m1", "Oi", 1_000, ConversationPerson("Ana"))),
        replyLabel = "Responder",
        markReadLabel = "Marcar como lida",
        replyEndpoint = JSONObject()
            .put("method", "POST")
            .put("url", "$base/chats/{chat_id}/notification-replies")
            .put("headers", JSONObject().put("Authorization", "Bearer {credential:user_id}"))
            .put("body", JSONObject().put("body", "{reply}").put("client_message_id", "{action_id}").put("idempotency_key", "r:{uuid}")),
        replyFailures = replyFailures(),
        dataJson = """{"chat_id":"42","user_id":"$account"}""",
    )

    @Test
    fun replyFailuresResolveStatusesServerMessagesAndOffline() {
        val config = replyFailures()
        assertEquals(ReplyFailure("Sem conexão.", true, true), ReplyFailure.resolve(config, 0, null))
        assertEquals(ReplyFailure("Sua sessão expirou.", false, false), ReplyFailure.resolve(config, 401, "ignored"))
        assertEquals(ReplyFailure("Aguarde 10 s.", true, true), ReplyFailure.resolve(config, 422, "Aguarde 10 s."))
        assertEquals(ReplyFailure("Não foi possível enviar.", true, true), ReplyFailure.resolve(config, 422, "x".repeat(161)))
        assertEquals(ReplyFailure("O servidor não respondeu.", true, true), ReplyFailure.resolve(config, 503, null))
    }

    @Test
    fun failedInlineReplyStaysWithReasonRetryAndDraft() {
        val key = "chat-${UUID.randomUUID()}"
        PamNotificationCredentials.replace(context, mapOf("user-a" to "token-a"))
        try {
            val firstBody: JSONObject
            LocalServer(
                response = "HTTP/1.1 422 Unprocessable\r\nContent-Type: application/json\r\nContent-Length: 34\r\n\r\n",
                body = """{"message":"Modo lento: aguarde."}""".toByteArray(Charsets.UTF_8),
            ).use { server ->
                PamConversationNotifications.show(context, failureConversation(key, "http://127.0.0.1:${server.port}"))
                PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Já vou")
                firstBody = JSONObject(String(server.request().body, Charsets.UTF_8))
                assertTrue(firstBody.getString("client_message_id").toLong() > 0)
                val event = drainAction(key)
                assertEquals(WireValue.Integer(422), event["statusCode"])
                assertEquals(WireValue.Flag(true), event["failureShown"])
                val failed = PamConversationNotifications.load(context, key)!!
                assertEquals("Modo lento: aguarde.", failed.failure!!.optString("text"))
                assertEquals("Já vou", failed.failure!!.optString("failedText"))
                assertEquals(1, failed.messages.size)
                val notification = PamConversationNotifications.build(context, failed).build()
                assertEquals(listOf("Tentar de novo", "Responder", "Marcar como lida"), notification.actions.map { it.title.toString() })
                assertEquals("Não enviada", notification.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString())
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
                assertEquals(listOf("Oi", "Já vou", "Modo lento: aguarde."), style.messages.map { it.text.toString() })
                val draft = PamConversationNotifications.openIntent(context, failed)!!
                assertEquals("Já vou", JSONObject(draft.getStringExtra("pam.notification.data")!!).getString("reply_draft"))
            }
            LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
                val failed = PamConversationNotifications.load(context, key)!!.failure!!
                PamConversationNotifications.show(context, failureConversation(key, "http://127.0.0.1:${server.port}").copy(messages = emptyList()))
                PamNotificationActions.handle(
                    context,
                    NotificationActionType.REPLY,
                    key,
                    failed.optString("failedText"),
                    retry = ReplyRetry(failed.optString("actionId"), failed.optString("uuid")),
                )
                val retried = JSONObject(String(server.request().body, Charsets.UTF_8))
                assertEquals(firstBody.getString("client_message_id"), retried.getString("client_message_id"))
                assertEquals(firstBody.getString("idempotency_key"), retried.getString("idempotency_key"))
                val event = drainAction(key)
                assertEquals(WireValue.Flag(false), event["failureShown"])
                val sent = PamConversationNotifications.load(context, key)!!
                assertNull(sent.failure)
                assertEquals("Já vou", sent.messages.last().text)
                assertNull(sent.messages.last().sender)
            }
        } finally {
            PamConversationNotifications.cancel(context, key)
            PamNotificationCredentials.clear(context)
        }
    }

    @Test
    fun replyWithoutCredentialShowsSignedOutWithoutReplyOrRetry() {
        val key = "chat-${UUID.randomUUID()}"
        PamNotificationCredentials.replace(context, mapOf("user-a" to "token-a"))
        try {
            LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
                PamConversationNotifications.show(context, failureConversation(key, "http://127.0.0.1:${server.port}", "user-b"))
                PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Oi")
                assertNull(server.requestOrNull(500))
                val failed = PamConversationNotifications.load(context, key)!!
                assertEquals("Sua sessão expirou.", failed.failure!!.optString("text"))
                val actions = PamConversationNotifications.build(context, failed).build().actions.map { it.title.toString() }
                assertEquals(listOf("Marcar como lida"), actions)
                assertEquals(WireValue.Flag(true), drainAction(key)["credentialMissing"])
            }
        } finally {
            PamConversationNotifications.cancel(context, key)
            PamNotificationCredentials.clear(context)
        }
    }

    @Test
    fun pushRulesHideTheReplyAndPreferTheMostSpecificMatchWithButtons() {
        val conversation = JSONObject()
            .put("type", "1").put("field", "chat_type")
            .put("kind", PamPushRendering.KIND_CONVERSATION)
            .put("conversation", JSONObject().put("key", "{chat_id}"))
            .put("message", JSONObject().put("sender", "{actor_name}").put("text", "{body}"))
            .put("reply", JSONObject().put("label", "Responder").put("hideWhen", JSONObject().put("can_reply", "0")))
        val generic = JSONObject().put("type", "5").put("kind", PamPushRendering.KIND_NOTIFICATION).put("title", "{title}").put("body", "{body}")
        val invite = JSONObject(generic.toString())
            .put("where", JSONObject().put("system_event", "post_collaboration_invite"))
            .put("requires", JSONArray().put("invite"))
            .put(
                "actions",
                JSONArray()
                    .put(JSONObject().put("id", "decline").put("label", "Recusar").put("dismiss", true)
                        .put("endpoint", JSONObject().put("method", "POST").put("url", "https://api.test/c/{invite}/respond").put("body", JSONObject().put("status", 3))))
                    .put(JSONObject().put("id", "accept").put("label", "Aceitar").put("dismiss", true)
                        .put("endpoint", JSONObject().put("method", "POST").put("url", "https://api.test/c/{invite}/respond").put("body", JSONObject().put("status", 2)))),
            )
        PamPushRendering.register(context, conversation.toString())
        PamPushRendering.register(context, generic.toString())
        PamPushRendering.register(context, invite.toString())
        val chat = "c${UUID.randomUUID().toString().take(8)}"
        try {
            PamActiveRoute.setForeground(false)
            val data = JSONObject().put("chat_type", "1").put("chat_id", chat).put("actor_name", "Ana").put("body", "Oi").put("can_reply", "0")
            assertTrue(PamPushRendering.render(context, "p1", "", "", data.toString()))
            assertEquals("", PamConversationNotifications.load(context, chat)!!.replyLabel)
            assertTrue(PamPushRendering.render(context, "p2", "", "", data.put("can_reply", "1").put("body", "De novo").toString()))
            assertEquals("Responder", PamConversationNotifications.load(context, chat)!!.replyLabel)

            val pushInvite = JSONObject().put("type", "5").put("system_event", "post_collaboration_invite").put("invite", "9").put("title", "Convite").put("body", "Ana")
            assertEquals(invite.getJSONObject("where").toString(), PamPushRendering.select(context, pushInvite)!!.optJSONObject("where")!!.toString())
            val withoutInvite = JSONObject().put("type", "5").put("system_event", "post_collaboration_invite").put("invite", " ").put("title", "Convite")
            assertNull(PamPushRendering.select(context, withoutInvite)!!.optJSONObject("where"))
            val other = JSONObject().put("type", "5").put("system_event", "admin_support").put("title", "Aviso").put("body", "Oi")
            assertNull(PamPushRendering.select(context, other)!!.optJSONObject("where"))
            val built = PamPushRendering.buildNotification(context, PamPushRendering.select(context, pushInvite)!!, "p3", "Convite", "Ana", "", pushInvite.toString())
            assertEquals(listOf("Recusar", "Aceitar"), built.actions.map { it.title.toString() })
        } finally {
            PamPushRendering.clear(context)
            PamConversationNotifications.cancel(context, chat)
        }
    }

    @Test
    fun notificationButtonSendsItsEndpointAndReportsTheId() {
        LocalServer(response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").use { server ->
            val endpoint = JSONObject().put("method", "POST").put("url", "http://127.0.0.1:${server.port}/c/{invite}/respond").put("body", JSONObject().put("status", 2))
            PamNotificationActions.handleButton(context, "accept", endpoint, """{"invite":"9"}""", "", "pam-push", 77, true)
            val request = server.request()
            assertTrue(request.head.startsWith("POST /c/9/respond HTTP/1.1"))
            var event: Map<String, WireValue>? = null
            while (event == null) {
                val next = CountDownLatch(1)
                PamNotificationActions.next { _, payload ->
                    val values = WireMap.decode(payload)
                    if ((values["action"] as? WireValue.Text)?.value == "accept") event = values
                    next.countDown()
                }
                assertTrue(next.await(5, TimeUnit.SECONDS))
            }
            assertEquals(WireValue.Integer(3), event!!["type"])
            assertEquals(WireValue.Integer(200), event!!["statusCode"])
        }
    }

    @Test
    fun imagePrefetchWarmsTheRendererDiskCache() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        LocalServer(response = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n", body = png).use { server ->
            val url = "http://127.0.0.1:${server.port}/avatar-${UUID.randomUUID()}.png"
            val module = ImagePrefetchModule(context)
            try {
                val result = call(module, "prefetch", mapOf(
                    "urls" to WireValue.Text(JSONArray().put(url).toString()),
                    "priority" to WireValue.Integer(4),
                ))
                assertEquals(WireValue.Integer(1), result["succeeded"])
                assertEquals(WireValue.Integer(png.size.toLong()), result["bytes"])
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val cached = File(File(context.cacheDir, "pam-images-v1"), "$digest.image")
                assertTrue(cached.isFile)
                assertNotNull(PamConversationNotifications.avatar(context, url))
                cached.delete()
            } finally {
                module.close()
            }
        }
    }

    private fun call(module: NativeModule, method: String, values: Map<String, WireValue>): Map<String, WireValue> {
        val latch = CountDownLatch(1)
        val status = AtomicReference<ModuleResultStatus>()
        val payload = AtomicReference<ByteArray>()
        module.invoke(method, WireMap.encode(values)) { resultStatus, bytes ->
            status.set(resultStatus)
            payload.set(bytes)
            latch.countDown()
        }
        assertTrue("$method timed out", latch.await(20, TimeUnit.SECONDS))
        check(status.get() == ModuleResultStatus.SUCCESS) { "$method failed: ${String(payload.get())}" }
        return if (payload.get().isEmpty()) emptyMap() else WireMap.decode(payload.get())
    }

    private class Request(val head: String, val body: ByteArray)

    /** One-shot HTTP/1.1 server that records the request and replies with a fixed response. */
    private class LocalServer(response: String, body: ByteArray = ByteArray(0)) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 20_000 }
        private val received = AtomicReference<Request>()
        private val failure = AtomicReference<Throwable>()
        private val done = CountDownLatch(1)
        val port: Int = socket.localPort
        private val worker = thread(name = "pam-capability-test-server") {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 20_000
                    val input = client.getInputStream()
                    val head = StringBuilder()
                    var length = 0
                    while (true) {
                        val line = line(input)
                        if (line.isEmpty()) break
                        head.append(line).append("\r\n")
                        if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                    }
                    val requestBody = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(requestBody, read, length - read)
                        require(count > 0) { "Request ended early" }
                        read += count
                    }
                    received.set(Request(head.toString(), requestBody))
                    client.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        write(body)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }

        fun request(): Request {
            assertTrue("server did not finish", done.await(20, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            return received.get()
        }

        fun requestOrNull(timeoutMillis: Long): Request? {
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
            return received.get()
        }

        override fun close() {
            socket.close()
            worker.join(5_000)
        }

        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                require(value >= 0) { "Connection closed" }
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
    private fun replyFailures(): JSONObject = JSONObject()
        .put("sender", "Não enviada")
        .put("retryLabel", "Tentar de novo")
        .put("draftField", "reply_draft")
        .put("offline", JSONObject().put("text", "Sem conexão.").put("retry", true).put("keepReply", true).put("serverMessage", false))
        .put(
            "statuses",
            JSONArray()
                .put(JSONObject().put("codes", JSONArray().put(401)).put("text", "Sua sessão expirou.").put("retry", false).put("keepReply", false).put("serverMessage", false))
                .put(JSONObject().put("codes", JSONArray().put(422)).put("text", "Não foi possível enviar.").put("retry", true).put("keepReply", true).put("serverMessage", true)),
        )
        .put("otherwise", JSONObject().put("text", "O servidor não respondeu.").put("retry", true).put("keepReply", true).put("serverMessage", false))








}
