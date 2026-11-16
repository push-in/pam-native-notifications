import Foundation
import UIKit
import UserNotifications
import PamNative

final class NotificationPushHandler: PushEventHandler {
    func didRegister(deviceToken: Data) { PluginPushNotifications.didRegister(deviceToken: deviceToken) }
    func didRefresh(token: String, provider: Int64) { PluginPushNotifications.didRefresh(token: token, provider: provider) }
    func didFailToRegister(error: Error) { PluginPushNotifications.didFailToRegister(error: error) }
    func didReceive(notification: UNNotification) { PluginPushNotifications.didReceive(notification: notification) }
    func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void) { PluginPushNotifications.didReceive(response: response, completionHandler: completionHandler) }
    func didReceiveRemote(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void) { PluginPushNotifications.didReceiveRemote(userInfo: userInfo, completion: completion) }
    // Launch hooks for `PushNotifications::takeLaunchNotification()`. They
    // match the signatures the core's PushEventHandler needs to forward
    // `connectionOptions.notificationResponse` and
    // `launchOptions[.remoteNotification]` before PHP boots.
    func didLaunch(response: UNNotificationResponse) { PluginPushNotifications.didLaunch(response: response) }
    func didLaunch(remoteNotification userInfo: [AnyHashable: Any]) { PluginPushNotifications.didLaunch(remoteNotification: userInfo) }
}
