package dev.pam.notifications

import org.junit.Assert.*
import org.junit.Test

class PushTokenRegistrationTest {
    private val endpoint = """{"method":"POST","url":"https://api.test/token","headers":{"Authorization":"Bearer {credential:account}"},"body":{"token":"{token}","provider_type":2}}"""
    private val credentials = mutableMapOf("a" to "credential-a", "b" to "credential-b")
    private val lookup: (String) -> String? = { credentials[it] }

    private fun configured(): TokenRegistrationState = TokenRegistrationState().apply {
        configure(endpoint, listOf("a", "b"))
        token("fcm-one")
        reconcile(lookup)
    }

    @Test fun coldProcessRestoresOfflineTokenConfigurationAndRetryBudget() {
        var state = configured()
        assertEquals(0L, state.due())
        val first = requireNotNull(state.begin(0, lookup))
        state.finish(first, 0, lookup)
        state = TokenRegistrationState.from(state.json()) // no Activity, listener or in-memory singleton
        val secondAccount = requireNotNull(state.begin(0, lookup))
        assertEquals("b", secondAccount.account)
        state.finish(secondAccount, 201, lookup)
        assertNull(state.begin(29_999, lookup))
        assertEquals("a", requireNotNull(state.begin(30_000, lookup)).account)
    }

    @Test fun duplicateTokenConfigurationAndCredentialsNeverResetSuccessOrRetries() {
        val state = configured()
        val first = requireNotNull(state.begin(0, lookup))
        state.finish(first, 201, lookup)
        val second = requireNotNull(state.begin(0, lookup))
        state.finish(second, 429, lookup)
        val saved = state.json()
        repeat(10) {
            state.configure(endpoint, listOf("a", "b")); state.token("fcm-one"); state.reconcile(lookup)
        }
        assertEquals(saved, state.json())
        assertNull(state.begin(29_999, lookup))
        assertEquals("b", requireNotNull(state.begin(30_000, lookup)).account)
    }

    @Test fun latestGenerationWinsAndAnOldResponseCannotAcknowledgeOrSpendItsBudget() {
        val state = configured()
        val old = requireNotNull(state.begin(0, lookup))
        state.token("fcm-two"); state.reconcile(lookup)
        val pending = state.json()
        assertFalse(state.current(old, lookup))
        state.finish(old, 201, lookup)
        assertEquals(pending, state.json())
        val current = requireNotNull(state.begin(0, lookup))
        assertEquals("fcm-two", current.token)
        state.finish(old, 503, lookup)
        state.finish(current, 201, lookup)
        assertEquals("b", requireNotNull(state.begin(0, lookup)).account)
    }

    @Test fun credentialRotationAfterSendCannotAcknowledgeTheNewSession() {
        val state = configured()
        val old = requireNotNull(state.begin(0, lookup))
        credentials["a"] = "replacement"
        assertFalse(state.current(old, lookup)) // immediate pre-send/ack check uses the actual credential
        state.reconcile(lookup)
        state.finish(old, 201, lookup)
        val replacement = requireNotNull(state.begin(0, lookup))
        assertEquals("a", replacement.account)
        assertEquals("replacement", replacement.credential)
        assertNotEquals(old.fingerprint, replacement.fingerprint)
    }

    @Test fun logoutPrunesWorkAndAnInFlightCompletionCannotResurrectIt() {
        val state = configured()
        val old = requireNotNull(state.begin(0, lookup))
        credentials.clear(); state.reconcile(lookup)
        state.finish(old, 0, lookup)
        assertNull(state.due())
        credentials["a"] = "credential-a"; state.reconcile(lookup)
        assertNotNull(state.begin(0, lookup)) // a signed-in account can be re-enabled
        state.unregister()
        state.finish(old, 201, lookup)
        assertNull(state.due())
        state.token("late-firebase-event"); state.reconcile(lookup)
        assertNull(state.due()) // unregister also invalidates configured accounts
        assertEquals(1L, TokenRegistrationState.from(state.json()).registrationEpoch)
    }

    @Test fun noCredentialsOrNoConfiguredAccountsCreatesNoWork() {
        val state = TokenRegistrationState()
        state.token("cold-token")
        assertNull(state.due())
        state.configure(endpoint, listOf("missing")); state.reconcile(lookup)
        assertNull(state.due())
        state.configure(endpoint, emptyList()); state.reconcile(lookup)
        assertNull(state.due())
    }

    @Test fun terminalHttpStatusesNeverLoopButNewCredentialsAreEligible() {
        for (status in listOf(301, 400, 401, 403, 404, 409, 422)) {
            val state = configured()
            repeat(2) { state.finish(requireNotNull(state.begin(0, lookup)), status, lookup) }
            state.configure(endpoint, listOf("a", "b")); state.reconcile(lookup)
            assertNull("status $status", state.due())
            credentials["a"] = "rotated-$status"; state.reconcile(lookup)
            assertEquals("a", requireNotNull(state.begin(0, lookup)).account)
        }
    }

    @Test fun retryableFailuresAreCappedAtEightIncludingProcessDeathReservations() {
        for (status in listOf(0, 408, 429, 500, 503, 599)) {
            var state = TokenRegistrationState().apply { configure(endpoint, listOf("a")); token("token"); reconcile(lookup) }
            var now = 0L
            repeat(8) { index ->
                val attempt = requireNotNull(state.begin(now, lookup))
                if (index % 2 == 0) state.finish(attempt, status, lookup) // alternate with process death before response
                state = TokenRegistrationState.from(state.json())
                now += TokenRegistrationState.backoff(index + 1)
            }
            assertNull("status $status", state.due())
            assertNull(state.begin(Long.MAX_VALUE, lookup))
        }
    }

    @Test fun endpointChangeInvalidatesPreviousSuccessWithoutLosingTheToken() {
        val state = configured()
        repeat(2) { state.finish(requireNotNull(state.begin(0, lookup)), 201, lookup) }
        assertNull(state.due())
        state.configure(endpoint.replace("api.test", "new.test"), listOf("a", "b")); state.reconcile(lookup)
        assertEquals("fcm-one", requireNotNull(state.begin(0, lookup)).token)
        assertFalse(state.json().contains("credential-a"))
        assertFalse(state.json().contains("credential-b"))
    }

    @Test fun anotherAccountChangingDoesNotDiscardThisAccountsSuccessfulAck() {
        val state = configured()
        val accountA = requireNotNull(state.begin(0, lookup))
        credentials.remove("b"); state.reconcile(lookup)
        assertTrue(state.current(accountA, lookup))
        state.finish(accountA, 201, lookup)
        assertNull(state.due())
    }

    @Test fun removedAndReaddedIdenticalAccountCannotAcknowledgeAnOldAttempt() {
        val state = configured()
        val old = requireNotNull(state.begin(0, lookup))
        credentials.remove("a"); state.reconcile(lookup)
        credentials["a"] = "credential-a"; state.reconcile(lookup)
        assertFalse(state.current(old, lookup))
        state.finish(old, 201, lookup)
        val attempts = listOfNotNull(state.begin(0, lookup), state.begin(0, lookup))
        assertEquals(setOf("a", "b"), attempts.map { it.account }.toSet())
        assertNotEquals(old.generation, attempts.first { it.account == "a" }.generation)
    }

    @Test fun cancellationBeforeNetworkDoesNotConsumeTheFiniteRetryBudget() {
        val state = TokenRegistrationState().apply { configure(endpoint, listOf("a")); token("token"); reconcile(lookup) }
        repeat(20) {
            val reserved = requireNotNull(state.begin(0, lookup))
            state.release(reserved, lookup)
            assertEquals(0L, state.due())
        }
        state.finish(requireNotNull(state.begin(0, lookup)), 201, lookup)
        assertNull(state.due())
    }

    @Test fun onlyARestoredReservationMayRequestAnotherWakeup() {
        val state = configured()
        val old = requireNotNull(state.begin(0, lookup))
        state.token("new-generation"); state.reconcile(lookup)
        val newGeneration = state.json()
        assertFalse(state.release(old, lookup))
        assertEquals(newGeneration, state.json())
        val current = requireNotNull(state.begin(0, lookup))
        assertTrue(state.release(current, lookup))
        val released = state.json()
        assertFalse(state.release(current, lookup))
        assertEquals(released, state.json())
        val revoked = requireNotNull(state.begin(0, lookup))
        credentials.remove(revoked.account)
        assertFalse(state.release(revoked, lookup))
        state.unregister()
        assertFalse(state.release(revoked, lookup))
        assertNull(state.due())
    }

}
