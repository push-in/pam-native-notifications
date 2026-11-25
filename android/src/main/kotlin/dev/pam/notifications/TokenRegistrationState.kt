package dev.pam.notifications

import org.json.JSONArray
import org.json.JSONObject

/** Persisted values use integer states. No bearer is serialized into this queue. */
internal enum class TokenDeliveryState(val wire: Int) {
    PENDING(1), ACKNOWLEDGED(2), REJECTED(3);

    companion object {
        fun from(wire: Int) = entries.firstOrNull { it.wire == wire } ?: REJECTED
    }
}

internal data class TokenDelivery(
    val fingerprint: String,
    val revision: Long,
    var attempts: Int = 0,
    var after: Long = 0,
    var state: TokenDeliveryState = TokenDeliveryState.PENDING,
)

internal data class TokenRegistrationAttempt(
    val generation: Long,
    val account: String,
    val token: String,
    val endpoint: String,
    val credential: String,
    val fingerprint: String,
)

/** Small deterministic state machine, reloaded from disk for every transaction. */
internal class TokenRegistrationState {
    var generation: Long = 0
        private set
    var registrationEpoch: Long = 0
        private set
    private var token: String? = null
    private var endpoint: String? = null
    private var accounts: List<String> = emptyList()
    private val deliveries = linkedMapOf<String, TokenDelivery>()

    fun configure(config: String, ids: List<String>) {
        if (endpoint == config && accounts == ids) return
        endpoint = config
        accounts = ids
        generation++
    }

    fun token(value: String) {
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 8_192) { "Invalid push token" }
        if (token == value) return
        token = value
        deliveries.clear()
        generation++
    }

    fun unregister() {
        registrationEpoch++
        token = null
        accounts = emptyList()
        deliveries.clear()
        generation++
    }

    fun reconcile(credentials: (String) -> String?) {
        val desired = linkedMapOf<String, String>()
        val currentToken = token
        val currentEndpoint = endpoint
        if (currentToken != null && currentEndpoint != null) {
            for (account in accounts) {
                val credential = credentials(account)?.takeIf(String::isNotEmpty) ?: continue
                desired[account] = fingerprint(currentToken, currentEndpoint, account, credential)
            }
        }
        var changed = deliveries.keys.retainAll(desired.keys)
        desired.forEach { (account, fingerprint) ->
            if (deliveries[account]?.fingerprint != fingerprint) {
                deliveries[account] = TokenDelivery(fingerprint, revision = generation + 1)
                changed = true
            }
        }
        if (changed) generation++
    }

    fun due(): Long? = deliveries.values.filter { it.state == TokenDeliveryState.PENDING && it.attempts < MAX_ATTEMPTS }
        .minOfOrNull { it.after }

    /** Reserve durably before I/O: process death cannot reset the retry budget. */
    fun begin(now: Long, credentials: (String) -> String?): TokenRegistrationAttempt? {
        reconcile(credentials)
        val entry = deliveries.entries.firstOrNull { (_, value) ->
            value.state == TokenDeliveryState.PENDING && value.attempts < MAX_ATTEMPTS && value.after <= now
        } ?: return null
        val credential = credentials(entry.key) ?: return null
        val candidate = TokenRegistrationAttempt(entry.value.revision, entry.key, token ?: return null, endpoint ?: return null, credential, entry.value.fingerprint)
        if (!current(candidate, credentials)) return null
        entry.value.attempts++
        // A killed/stopped worker retries no sooner than this durable deadline.
        entry.value.after = now + backoff(entry.value.attempts)
        if (entry.value.attempts == MAX_ATTEMPTS) entry.value.state = TokenDeliveryState.REJECTED
        return candidate
    }

    fun current(attempt: TokenRegistrationAttempt, credentials: (String) -> String?): Boolean =
        deliveries[attempt.account]?.revision == attempt.generation && token == attempt.token && endpoint == attempt.endpoint &&
            attempt.account in accounts && deliveries[attempt.account]?.fingerprint == attempt.fingerprint &&
            credentials(attempt.account) == attempt.credential

    /** A stop/consent change before opening the network did not consume a try. */
    fun release(attempt: TokenRegistrationAttempt, credentials: (String) -> String?): Boolean {
        if (!current(attempt, credentials)) return false
        val delivery = deliveries[attempt.account] ?: return false
        if (delivery.attempts == 0 || delivery.after == 0L || delivery.state == TokenDeliveryState.ACKNOWLEDGED) return false
        delivery.attempts--
        delivery.after = 0
        delivery.state = TokenDeliveryState.PENDING
        return true
    }

    fun finish(attempt: TokenRegistrationAttempt, status: Int, credentials: (String) -> String?) {
        if (!current(attempt, credentials)) return
        val delivery = deliveries[attempt.account] ?: return
        delivery.state = when {
            status in 200..299 -> TokenDeliveryState.ACKNOWLEDGED
            retryable(status) && delivery.attempts < MAX_ATTEMPTS -> TokenDeliveryState.PENDING
            else -> TokenDeliveryState.REJECTED
        }
    }

    fun json(): String = JSONObject().put("generation", generation).put("registrationEpoch", registrationEpoch).put("token", token ?: JSONObject.NULL)
        .put("endpoint", endpoint ?: JSONObject.NULL).put("accounts", JSONArray(accounts))
        .put("deliveries", JSONObject().apply {
            deliveries.forEach { (account, value) -> put(account, JSONObject().put("fingerprint", value.fingerprint).put("revision", value.revision)
                .put("attempts", value.attempts).put("after", value.after).put("state", value.state.wire)) }
        }).toString()

    companion object {
        const val MAX_ATTEMPTS = 8
        fun retryable(status: Int) = status <= 0 || status == 408 || status == 429 || status in 500..599
        fun backoff(attempts: Int): Long = (30_000L shl (attempts - 1).coerceIn(0, MAX_ATTEMPTS - 1)).coerceAtMost(3_600_000L)
        private fun fingerprint(token: String, endpoint: String, account: String, credential: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(JSONArray(listOf(token, endpoint, account, credential)).toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun from(json: String?): TokenRegistrationState {
            val result = TokenRegistrationState()
            if (json == null) return result
            val source = JSONObject(json)
            result.generation = source.optLong("generation")
            result.registrationEpoch = source.optLong("registrationEpoch")
            result.token = source.optString("token").takeUnless { source.isNull("token") || it.isBlank() }
            result.endpoint = source.optString("endpoint").takeUnless { source.isNull("endpoint") || it.isBlank() }
            val accounts = source.optJSONArray("accounts") ?: JSONArray()
            require(accounts.length() <= 64)
            result.accounts = (0 until accounts.length()).map(accounts::getString)
            val stored = source.optJSONObject("deliveries") ?: JSONObject()
            result.accounts.forEach { account -> stored.optJSONObject(account)?.let { value ->
                result.deliveries[account] = TokenDelivery(value.getString("fingerprint"), value.optLong("revision", result.generation), value.optInt("attempts").coerceIn(0, MAX_ATTEMPTS),
                    value.optLong("after").coerceAtLeast(0), TokenDeliveryState.from(value.optInt("state")))
            } }
            return result
        }
    }
}
