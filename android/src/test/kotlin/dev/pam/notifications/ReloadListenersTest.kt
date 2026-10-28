package dev.pam.notifications
import dev.pam.nativeapp.modules.*
import org.junit.Test
import org.junit.Assert.*
class ReloadListenersTest {
    @Test
    fun pushWaiterCanBeRearmedAfterReload() {
        var staleCompletions = 0
        var activeCompletions = 0
        PamPushNotifications.next(ModuleCompletion { _, _ -> staleCompletions++ })

        PamPushNotifications.prepareReload()
        PamPushNotifications.next(ModuleCompletion { status, _ ->
            if (status == ModuleResultStatus.SUCCESS) activeCompletions++
        })
        PamPushNotifications.reportReceived(id = "reload-safe")

        assertEquals(0, staleCompletions)
        assertEquals(1, activeCompletions)
    }
}
