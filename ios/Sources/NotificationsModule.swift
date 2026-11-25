import PamNative
import Foundation
import UserNotifications
import UIKit

/// Turns the APNs device token into the token PHP registers with
/// `Notifications::registerPush()`. The generated plugin package installs one
/// (Firebase Messaging) when the project ships `GoogleService-Info.plist`, so
/// iOS reports an FCM token (`PushProvider::Fcm`) exactly like Android and the
/// same server sends to both. Without it the raw APNs token is reported.
public enum PluginPushNotifications {
    /// `Pam\Native\PushProvider` values.
    public static let providerFcm: Int64 = 1
    public static let providerApns: Int64 = 2
    /// `PushNotifications::LAUNCH_ENV`: the tap that started the process,
    /// exported before PHP boots for `takeLaunchNotification()`.
    public static let launchEnv = "PAM_NATIVE_NOTIFICATIONS_LAUNCH"

    /// Installed before PHP starts (generated `PamNativePluginRegistry.modules()`).
    public static var tokenProvider: PamPushTokenProvider? {
        get { PamPushNotifications.tokenProvider }
        set { PamPushNotifications.tokenProvider = newValue }
    }

    public static func didRegister(deviceToken: Data) {
        guard let provider = tokenProvider else {
            PushTokenRegistry.shared.resolve(Self.hex(deviceToken), provider: providerApns)
            return
        }
        let kind = provider.provider
        provider.token(apnsToken: deviceToken) { result in
            switch result {
            case let .success(token) where !token.isEmpty:
                PushTokenRegistry.shared.resolve(token, provider: kind)
            case .success:
                PushTokenRegistry.shared.reject("Push provider returned an empty token")
            case let .failure(error):
                PushTokenRegistry.shared.reject(error.localizedDescription)
            }
        }
    }

    /// The provider rotated its token (FCM `didReceiveRegistrationToken`):
    /// the next `registerPush()` answers with it.
    public static func didRefresh(token: String, provider: Int64) {
        guard !token.isEmpty else { return }
        PushTokenRegistry.shared.refresh(token, provider: provider)
    }

    static func hex(_ token: Data) -> String {
        token.map { String(format: "%02x", $0) }.joined()
    }

    public static func didFailToRegister(error: Error) {
        PushTokenRegistry.shared.reject(error.localizedDescription)
    }

    public static func didReceive(notification: UNNotification) {
        PushTokenRegistry.shared.report(
            event: 1,
            request: notification.request
        )
    }

    public static func didOpen(response: UNNotificationResponse) {
        // UIKit hands the launch tap to the delegate after the host already
        // captured it (didLaunch): it is queued once, not twice.
        if PushTokenRegistry.shared.isLaunchEcho(response.notification.request) { return }
        PushTokenRegistry.shared.report(
            event: 2,
            request: response.notification.request
        )
    }

    /// The tap that cold-started the app, from
    /// `UIScene.ConnectionOptions.notificationResponse`. The host must call it
    /// before the runtime starts (`scene(_:willConnectTo:options:)`, before
    /// booting PHP): PHP reads it synchronously with
    /// `PushNotifications::takeLaunchNotification()`; `listen()` still
    /// receives it when PHP does not take it. Only the first launch per
    /// process counts; action buttons and dismissals are ignored.
    public static func didLaunch(response: UNNotificationResponse) {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }
        PushTokenRegistry.shared.captureLaunch(request: response.notification.request)
    }

    /// `launchOptions[.remoteNotification]` (hosts without a scene delegate),
    /// before the runtime starts. Installed automatically through
    /// `UIApplication.didFinishLaunchingNotification` (see
    /// `observeLaunchOptions()`); calling it again for the same launch is a
    /// no-op.
    public static func didLaunch(remoteNotification userInfo: [AnyHashable: Any]) {
        PushTokenRegistry.shared.captureLaunch(remoteNotification: userInfo)
    }

    private static let launchLock = NSLock()
    private static var launchObserver: NSObjectProtocol?

    /// `UIApplication.didFinishLaunchingNotification` carries the launch
    /// options and is posted right after
    /// `application(_:didFinishLaunchingWithOptions:)` returns, before the
    /// host's deferred runtime start. Registered from `NotificationsModule`,
    /// which `PamNativePluginRegistry.modules()` builds inside that method.
    /// Scene-based hosts usually get no `.remoteNotification` here; they
    /// must forward `connectionOptions.notificationResponse` instead.
    static func observeLaunchOptions() {
        launchLock.lock()
        defer { launchLock.unlock() }
        guard launchObserver == nil else { return }
        launchObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didFinishLaunchingNotification,
            object: nil,
            queue: nil
        ) { notification in
            launchLock.lock()
            let observer = launchObserver
            launchLock.unlock()
            if let observer { NotificationCenter.default.removeObserver(observer) }
            guard let userInfo = notification.userInfo?[UIApplication.LaunchOptionsKey.remoteNotification.rawValue]
                as? [AnyHashable: Any] else { return }
            didLaunch(remoteNotification: userInfo)
        }
    }

    /// UNUserNotificationCenterDelegate `didReceive`: handles conversation
    /// inline replies / mark-as-read natively (endpoint delivery, durable
    /// Notifications::onAction() queue) and reports other taps as opens.
    /// Call `completionHandler` exactly as UIKit hands it in.
    public static func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        // APNs pushes whose category belongs to a PushRendering rule (reply,
        // mark-as-read, retry, buttons) are answered natively.
        if userInfo[PamConversationNotifications.userInfoKey] == nil,
           response.actionIdentifier != UNNotificationDefaultActionIdentifier,
           response.actionIdentifier != UNNotificationDismissActionIdentifier {
            let token = PamBackgroundTaskToken()
            let finish = PamMainCallback(completionHandler)
            token.begin("pam-notification-category-action")
            let handled = PamNotificationCategories.handle(response) {
                DispatchQueue.main.async {
                    finish.call()
                    token.end()
                }
            }
            if handled { return }
            token.end()
        }
        guard let key = userInfo[PamConversationNotifications.userInfoKey] as? String,
              response.actionIdentifier == PamConversationNotifications.replyAction ||
                response.actionIdentifier == PamNotificationCategories.retryAction ||
                response.actionIdentifier == PamConversationNotifications.markReadAction else {
            if response.actionIdentifier != UNNotificationDismissActionIdentifier {
                didOpen(response: response)
            }
            completionHandler()
            return
        }
        let retrying = response.actionIdentifier == PamNotificationCategories.retryAction
        let type = response.actionIdentifier == PamConversationNotifications.markReadAction
            ? PamNotificationActionType.markRead
            : PamNotificationActionType.reply
        let retry: (actionId: String, uuid: String)? = retrying
            ? ((userInfo[PamNotificationCategories.retryActionIdKey] as? String) ?? "", (userInfo[PamNotificationCategories.retryUuidKey] as? String) ?? "")
            : nil
        let text = String(
            ((response as? UNTextInputNotificationResponse)?.userText
                ?? (retrying ? userInfo[PamNotificationCategories.retryTextKey] as? String : nil) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(PamConversationMessage.maxText)
        )
        if type == PamNotificationActionType.reply && text.isEmpty {
            completionHandler()
            return
        }
        // Keep the process alive while the endpoint request runs.
        let token = PamBackgroundTaskToken()
        let finish = PamMainCallback(completionHandler)
        DispatchQueue.main.async {
            token.begin("pam-notification-action")
            PamNotificationActions.handle(type: type, key: key, text: text, retry: retry) {
                DispatchQueue.main.async {
                    finish.call()
                    token.end()
                }
            }
        }
    }

    /// `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`
    /// for data-only (`content-available: 1`) pushes: applies PushRendering
    /// rules natively, then reports the push to PHP (`PushMessage::$rendered`).
    public static func didReceiveRemote(
        userInfo: [AnyHashable: Any],
        completion: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        var data: [String: Any] = [:]
        for (key, value) in userInfo {
            guard let key = key as? String, key != "aps" else { continue }
            data[key] = value
        }
        let aps = userInfo["aps"] as? [String: Any]
        let alert = aps?["alert"] as? [String: Any]
        let title = (alert?["title"] as? String) ?? ""
        let body = (alert?["body"] as? String) ?? (aps?["alert"] as? String) ?? ""
        let id = (userInfo["gcm.message_id"] as? String)
            ?? (userInfo["google.message_id"] as? String)
            ?? (userInfo["id"] as? String)
            ?? UUID().uuidString
        let dataJson = JSONSerialization.isValidJSONObject(data)
            ? (try? JSONSerialization.data(withJSONObject: data)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
            : "{}"
        DispatchQueue.global(qos: .userInitiated).async {
            let rendered = PamPushRendering.render(id: id, title: title, body: body, dataJson: dataJson)
            PushTokenRegistry.shared.report(
                event: 1,
                id: id,
                title: title,
                body: body,
                dataJson: dataJson,
                deepLink: (data["pam.deepLink"] as? String) ?? (data["deepLink"] as? String) ?? (data["deep_link"] as? String) ?? "",
                rendered: rendered
            )
            // Give UserNotifications a moment to persist the posted request.
            DispatchQueue.main.asyncAfter(deadline: .now() + (rendered ? 0.5 : 0)) {
                completion(rendered ? .newData : .noData)
            }
        }
    }
}

public final class NotificationsModule: NativeModule, ClosableNativeModule {
    public init() {
        PamPushNotifications.handler = NotificationPushHandler()
        PluginPushNotifications.observeLaunchOptions()
    }
    // UserNotifications requires a real application bundle. Resolve it only
    // when the notifications capability is invoked so registry composition,
    // package tests, and non-application hosts remain safe.
    private lazy var center = UNUserNotificationCenter.current()

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            switch method {
            case "requestPermission":
                center.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
                    if let error {
                        completion(.failure, Data(error.localizedDescription.utf8))
                    } else {
                        let result = (try? WireMap.encode(["granted": .flag(granted)])) ?? Data()
                        completion(.success, result)
                    }
                }
            case "schedule":
                let values = try WireMap.decode(payload)
                guard case let .text(id)? = values["id"],
                      case let .text(title)? = values["title"],
                      case let .text(body)? = values["body"] else {
                    throw NotificationsError("Invalid notification payload")
                }
                let delay: Int64
                if case let .integer(value)? = values["delaySeconds"] {
                    delay = max(0, value)
                } else {
                    delay = 0
                }
                let content = UNMutableNotificationContent()
                content.title = title
                content.body = body
                content.sound = .default
                var userInfo: [AnyHashable: Any] = [:]
                if case let .text(dataJSON)? = values["data"],
                   let data = dataJSON.data(using: .utf8),
                   let object = try? JSONSerialization.jsonObject(with: data) {
                    userInfo["pam.data"] = object
                }
                if case let .text(deepLink)? = values["deepLink"], !deepLink.isEmpty {
                    userInfo["pam.deepLink"] = deepLink
                }
                content.userInfo = userInfo
                let trigger = delay > 0
                    ? UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(delay), repeats: false)
                    : nil
                center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger)) {
                    if let error = $0 {
                        completion(.failure, Data(error.localizedDescription.utf8))
                    } else {
                        completion(.success, Data())
                    }
                }
            case "cancel":
                let values = try WireMap.decode(payload)
                guard case let .text(id)? = values["id"] else {
                    throw NotificationsError("Missing notification id")
                }
                center.removePendingNotificationRequests(withIdentifiers: [id])
                center.removeDeliveredNotifications(withIdentifiers: [id])
                completion(.success, Data())
            case "registerPush":
                PushTokenRegistry.shared.register(completion: completion)
            case "unregisterPush":
                DispatchQueue.main.async {
                    PluginPushNotifications.tokenProvider?.deleteToken()
                    UIApplication.shared.unregisterForRemoteNotifications()
                    PushTokenRegistry.shared.unregister()
                    completion(.success, Data())
                }
            case "nextPushEvent":
                PushTokenRegistry.shared.nextEvent(completion: completion)
            case "configurePushEvents":
                // iOS keeps push events in memory only already.
                completion(.success, Data())
            case "consumeLaunchNotification":
                let values = try WireMap.decode(payload)
                if case let .text(token)? = values["launch"] {
                    PushTokenRegistry.shared.consumeLaunch(token)
                }
                completion(.success, Data())
            case "showConversation":
                let values = try WireMap.decode(payload)
                guard case let .text(specJSON)? = values["spec"],
                      let object = try JSONSerialization.jsonObject(with: Data(specJSON.utf8)) as? [String: Any] else {
                    throw NotificationsError("Invalid conversation payload")
                }
                let spec = try PamConversationSpec.from(object)
                PamConversationNotifications.show(spec) { error in
                    if let error {
                        completion(.failure, Data(error.localizedDescription.utf8))
                    } else {
                        completion(.success, (try? WireMap.encode(["key": .text(spec.key)])) ?? Data())
                    }
                }
            case "cancelConversation":
                let values = try WireMap.decode(payload)
                guard case let .text(key)? = values["key"] else {
                    throw NotificationsError("Missing conversation key")
                }
                PamConversationNotifications.cancel(key: key)
                completion(.success, Data())
            case "nextAction":
                PamNotificationActions.next(completion)
            case "registerPushRendering":
                let values = try WireMap.decode(payload)
                guard case let .text(rule)? = values["rule"] else { throw NotificationsError("Missing push rendering rule") }
                try PamPushRendering.register(rule)
                completion(.success, Data())
            case "forgetPushRendering":
                let values = try WireMap.decode(payload)
                if case .flag(true)? = values["all"] {
                    PamPushRendering.clear()
                } else {
                    guard case let .text(field)? = values["field"], case let .text(type)? = values["type"] else {
                        throw NotificationsError("Missing push rendering field/type")
                    }
                    PamPushRendering.forget(field: field, type: type)
                }
                completion(.success, Data())
            case "configureTokenRegistration":
                // Android's headless FCM queue is additive. iOS keeps its
                // existing foreground/APNs registration path unchanged.
                completion(.success, Data())
            case "setCredential":
                let values = try WireMap.decode(payload)
                guard case let .text(account)? = values["account"], case let .text(token)? = values["token"] else {
                    throw NotificationsError("Missing credential account/token")
                }
                try PamNotificationCredentials.set(account: account, token: token)
                completion(.success, Data())
            case "removeCredential":
                let values = try WireMap.decode(payload)
                guard case let .text(account)? = values["account"] else { throw NotificationsError("Missing credential account") }
                PamNotificationCredentials.remove(account: account)
                completion(.success, Data())
            case "replaceCredentials":
                let values = try WireMap.decode(payload)
                guard case let .text(json)? = values["tokens"],
                      let tokens = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: String] else {
                    throw NotificationsError("Invalid credential map")
                }
                try PamNotificationCredentials.replace(tokens)
                completion(.success, Data())
            case "setActiveRoute":
                let values = try WireMap.decode(payload)
                guard case let .text(name)? = values["name"] else { throw NotificationsError("Missing route name") }
                var params = "{}"
                var path = ""
                if case let .text(value)? = values["params"] { params = value }
                if case let .text(value)? = values["path"] { path = value }
                PamActiveRoute.update(name: name, paramsJson: params, path: path)
                completion(.success, Data())
            default:
                throw NotificationsError("Unknown notifications method \(method)")
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    public func close() {
        PushTokenRegistry.shared.closeEvents()
        PamNotificationActions.close("Notifications module closed")
    }
}

final class PushTokenRegistry {
    static let shared = PushTokenRegistry()
    private let lock = NSLock()
    private var token: String?
    private var provider = PluginPushNotifications.providerApns
    private var retainedProvider: PamPushTokenProvider?

    var tokenProvider: PamPushTokenProvider? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return retainedProvider
        }
        set {
            lock.lock()
            retainedProvider = newValue
            // A token from another provider must never be reported as this one.
            token = nil
            lock.unlock()
        }
    }
    private var waiters: [ModuleCompletion] = []
    /// `registerForRemoteNotifications()` already asked in this process: a
    /// second request (another `registerPush()`, a cached token) only makes
    /// iOS and Firebase log a duplicate APNs registration.
    private var apnsRequested = false
    /// Asks iOS for the APNs device token (replaced in tests).
    var requestApnsToken: () -> Void = {
        UIApplication.shared.registerForRemoteNotifications()
    }
    private var events: [Data] = []
    private var eventWaiter: ModuleCompletion?
    /// The tap that started this process (`takeLaunchNotification()`): its
    /// token and queued payload until PHP takes it or the listener gets it.
    private var launchCaptured = false
    private var launchToken: String?
    private var launchPayload: Data?
    /// UIKit still reports the launch tap to the notification delegate,
    /// right after launch; a later tap is never mistaken for that copy.
    private var launchEchoPending = false
    private var launchEchoDeadline = Date.distantPast
    private var launchIdentifier: String?
    private var launchUserInfo: NSDictionary?

    /// The cached token and its `PushProvider` value.
    func current() -> (token: String, provider: Int64)? {
        lock.lock()
        defer { lock.unlock() }
        return token.map { ($0, provider) }
    }

    func register(completion: @escaping ModuleCompletion) {
        lock.lock()
        if let token {
            let provider = provider
            // Apple asks for a registration on every launch: a rotated APNs
            // token still reaches the provider (and the cached token). Once
            // per process is enough.
            let request = !apnsRequested
            apnsRequested = true
            lock.unlock()
            completion(.success, payload(token, provider: provider))
            if request {
                DispatchQueue.main.async { [weak self] in self?.requestApnsToken() }
            }
            return
        }
        // Concurrent callers share the registration already in flight.
        let request = waiters.isEmpty && !apnsRequested
        waiters.append(completion)
        if request { apnsRequested = true }
        lock.unlock()
        if request {
            DispatchQueue.main.async { [weak self] in self?.requestApnsToken() }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            self?.reject("APNs token registration timed out")
        }
    }

    func resolve(_ value: String, provider: Int64) {
        lock.lock()
        token = value
        self.provider = provider
        let callbacks = waiters
        waiters.removeAll()
        lock.unlock()
        callbacks.forEach { $0(.success, payload(value, provider: provider)) }
    }

    func refresh(_ value: String, provider: Int64) {
        lock.lock()
        let pending = !waiters.isEmpty
        if !pending {
            token = value
            self.provider = provider
        }
        lock.unlock()
        if pending { resolve(value, provider: provider) }
    }

    func unregister() {
        lock.lock()
        token = nil
        apnsRequested = false
        let callbacks = waiters
        waiters.removeAll()
        lock.unlock()
        callbacks.forEach { $0(.failure, Data("Push registration was cancelled".utf8)) }
    }

    func reject(_ message: String) {
        lock.lock()
        guard token == nil, !waiters.isEmpty else {
            lock.unlock()
            return
        }
        // A failed or timed-out registration may be retried.
        apnsRequested = false
        let callbacks = waiters
        waiters.removeAll()
        lock.unlock()
        callbacks.forEach { $0(.failure, Data(message.utf8)) }
    }

    func nextEvent(completion: @escaping ModuleCompletion) {
        lock.lock()
        if !events.isEmpty {
            let payload = events.removeFirst()
            launchDeliveredLocked(payload)
            lock.unlock()
            completion(.success, payload)
            return
        }
        guard eventWaiter == nil else {
            lock.unlock()
            completion(.failure, Data("Only one push listener can wait at a time".utf8))
            return
        }
        eventWaiter = completion
        lock.unlock()
    }

    typealias PushFields = (id: String, title: String, body: String, dataJson: String, deepLink: String)

    static func fields(_ request: UNNotificationRequest) -> PushFields {
        let content = request.content
        var result = Self.fields(userInfo: content.userInfo, id: request.identifier)
        result.title = content.title
        result.body = content.body
        return result
    }

    /// A push known only by its APNs payload (`launchOptions[.remoteNotification]`).
    static func fields(userInfo: [AnyHashable: Any], id: String? = nil) -> PushFields {
        let dataObject = userInfo["pam.data"] ?? userInfo
        let data: Data
        if JSONSerialization.isValidJSONObject(dataObject),
           let encoded = try? JSONSerialization.data(withJSONObject: dataObject) {
            data = encoded.count <= 256 * 1_024 ? encoded : Data("{}".utf8)
        } else {
            data = Data("{}".utf8)
        }
        let deepLink = userInfo["pam.deepLink"] as? String
            ?? userInfo["deepLink"] as? String
            ?? userInfo["deep_link"] as? String
            ?? ""
        let aps = userInfo["aps"] as? [String: Any]
        let alert = aps?["alert"] as? [String: Any]
        let identifier = id
            ?? (userInfo["gcm.message_id"] as? String)
            ?? (userInfo["google.message_id"] as? String)
            ?? (userInfo["id"] as? String)
            ?? ""
        return (
            id: identifier,
            title: (alert?["title"] as? String) ?? "",
            body: (alert?["body"] as? String) ?? (aps?["alert"] as? String) ?? "",
            dataJson: String(decoding: data, as: UTF8.self),
            deepLink: deepLink
        )
    }

    func report(event: Int64, request: UNNotificationRequest) {
        let fields = Self.fields(request)
        report(
            event: event,
            id: fields.id,
            title: fields.title,
            body: fields.body,
            dataJson: fields.dataJson,
            deepLink: fields.deepLink,
            rendered: false
        )
    }

    func report(
        event: Int64,
        id: String,
        title: String,
        body: String,
        dataJson: String,
        deepLink: String,
        rendered: Bool
    ) {
        deliver(Self.encode(
            event: event,
            fields: (id: id, title: title, body: body, dataJson: dataJson, deepLink: deepLink),
            rendered: rendered,
            launch: nil
        ))
    }

    static func encode(event: Int64, fields: PushFields, rendered: Bool, launch: String?) -> Data {
        let data = fields.dataJson.utf8.count <= 256 * 1_024 ? fields.dataJson : "{}"
        var values: [String: WireValue] = [
            "event": .integer(event),
            "id": .text(String(fields.id.prefix(512))),
            "title": .text(String(fields.title.prefix(4_096))),
            "body": .text(String(fields.body.prefix(16_384))),
            "data": .text(data),
            "deepLink": .text(String(fields.deepLink.prefix(8_192))),
            "rendered": .flag(rendered),
        ]
        if let launch, !launch.isEmpty { values["launch"] = .text(launch) }
        return (try? WireMap.encode(values)) ?? Data()
    }

    private func deliver(_ payload: Data) {
        lock.lock()
        let callback = eventWaiter
        if callback == nil {
            if events.count >= 64 { events.removeFirst() }
            events.append(payload)
        } else {
            eventWaiter = nil
            launchDeliveredLocked(payload)
        }
        lock.unlock()
        callback?(.success, payload)
    }

    // MARK: Launch notification (PushNotifications::takeLaunchNotification)

    func captureLaunch(request: UNNotificationRequest) {
        captureLaunch(Self.fields(request), identifier: request.identifier, userInfo: request.content.userInfo)
    }

    func captureLaunch(remoteNotification userInfo: [AnyHashable: Any]) {
        captureLaunch(Self.fields(userInfo: userInfo), identifier: nil, userInfo: userInfo)
    }

    /// Exports the tap before PHP boots and queues it as an `Opened` event
    /// for `listen()`. Whichever path PHP uses first consumes the other.
    private func captureLaunch(_ fields: PushFields, identifier: String?, userInfo: [AnyHashable: Any]) {
        let token = UUID().uuidString
        let payload = Self.encode(event: 2, fields: fields, rendered: false, launch: token)
        lock.lock()
        guard !launchCaptured else {
            lock.unlock()
            return
        }
        launchCaptured = true
        launchToken = token
        launchPayload = payload
        launchEchoPending = true
        launchEchoDeadline = Date().addingTimeInterval(30)
        launchIdentifier = identifier
        launchUserInfo = userInfo as NSDictionary
        exportLaunchLocked(payload.base64EncodedString())
        lock.unlock()
        deliver(payload)
    }

    /// `consumeLaunchNotification`: PHP took the launch tap synchronously.
    func consumeLaunch(_ token: String) {
        lock.lock()
        defer { lock.unlock() }
        guard !token.isEmpty, token == launchToken else { return }
        if let payload = launchPayload, let index = events.firstIndex(of: payload) {
            events.remove(at: index)
        }
        launchToken = nil
        launchPayload = nil
        exportLaunchLocked("")
    }

    /// The delegate's copy of the captured launch tap (same request, or the
    /// same APNs payload when only the launch options were known).
    func isLaunchEcho(_ request: UNNotificationRequest) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard launchEchoPending else { return false }
        let same = Date() < launchEchoDeadline
            && ((launchIdentifier != nil && request.identifier == launchIdentifier)
                || launchUserInfo?.isEqual(to: request.content.userInfo) == true)
        if same || Date() >= launchEchoDeadline {
            launchEchoPending = false
            launchIdentifier = nil
            launchUserInfo = nil
        }
        return same
    }

    /// The listener received the launch tap: PHP must not take it again.
    private func launchDeliveredLocked(_ payload: Data) {
        guard let launchPayload, payload == launchPayload else { return }
        launchToken = nil
        self.launchPayload = nil
        exportLaunchLocked("")
    }

    /// Process environment, read by PHP's getenv(): set before PHP boots,
    /// emptied once consumed so a later PHP request (hot reload, crash
    /// recovery) does not see it. Libc rewrites an owned, longer value in
    /// place, which a concurrent getenv tolerates.
    private func exportLaunchLocked(_ value: String) {
        setenv(PluginPushNotifications.launchEnv, value, 1)
    }

    func closeEvents() {
        lock.lock()
        let callback = eventWaiter
        eventWaiter = nil
        lock.unlock()
        callback?(.failure, Data("Notifications module closed".utf8))
    }

    func payload(_ value: String, provider: Int64) -> Data {
        (try? WireMap.encode([
            "token": .text(value),
            "provider": .integer(provider),
        ])) ?? Data()
    }
}

struct NotificationsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Carries a UIKit completion handler across queues.
final class PamMainCallback: @unchecked Sendable {
    private let callback: () -> Void
    init(_ callback: @escaping () -> Void) { self.callback = callback }
    func call() { callback() }
}
