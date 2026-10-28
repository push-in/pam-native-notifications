import UIKit
import XCTest
import PamNative
@testable import PamNativeNotifications

/// Notifications::registerPush() on iOS: raw APNs without a provider, the FCM
/// token (PushProvider::Fcm = 1) when Firebase Messaging is installed.
final class PamPushTokenProviderTests: XCTestCase {
    private final class FakeProvider: PamPushTokenProvider {
        let provider = PluginPushNotifications.providerFcm
        var result: Result<String, Error> = .success("fcm-token-1")
        var received: [Data] = []
        var deleted = 0

        func token(apnsToken: Data, completion: @escaping (Result<String, Error>) -> Void) {
            received.append(apnsToken)
            completion(result)
        }

        func deleteToken() { deleted += 1 }
    }

    private struct Failure: LocalizedError {
        var errorDescription: String? { "no network" }
    }

    override func tearDown() {
        PluginPushNotifications.tokenProvider = nil
        PushTokenRegistry.shared.unregister()
        PushTokenRegistry.shared.requestApnsToken = { UIApplication.shared.registerForRemoteNotifications() }
        super.tearDown()
    }

    /// Counts APNs registration requests (main queue) after the registry settles.
    private func apnsRequests(_ body: () -> Void) -> Int {
        var requests = 0
        PushTokenRegistry.shared.requestApnsToken = { requests += 1 }
        body()
        let settled = expectation(description: "main queue")
        DispatchQueue.main.async { settled.fulfill() }
        wait(for: [settled], timeout: 2)
        return requests
    }

    func testConcurrentRegistrationsShareOneApnsRequest() {
        PushTokenRegistry.shared.unregister()
        let requests = apnsRequests {
            PushTokenRegistry.shared.register { _, _ in }
            PushTokenRegistry.shared.register { _, _ in }
        }
        XCTAssertEqual(requests, 1)
    }

    func testCachedTokenAsksApnsOncePerProcess() {
        PluginPushNotifications.tokenProvider = nil
        PluginPushNotifications.didRegister(deviceToken: Data([0x0B]))
        var answers = 0
        let requests = apnsRequests {
            PushTokenRegistry.shared.register { status, _ in if status == .success { answers += 1 } }
            PushTokenRegistry.shared.register { status, _ in if status == .success { answers += 1 } }
        }
        XCTAssertEqual(answers, 2)
        XCTAssertEqual(requests, 1)
    }

    func testFailedRegistrationMayAskApnsAgain() {
        PushTokenRegistry.shared.unregister()
        let first = apnsRequests { PushTokenRegistry.shared.register { _, _ in } }
        PushTokenRegistry.shared.reject("APNs failed")
        let retry = apnsRequests { PushTokenRegistry.shared.register { _, _ in } }
        XCTAssertEqual(first + retry, 2)
    }

    func testRawApnsTokenWithoutProvider() {
        PluginPushNotifications.tokenProvider = nil
        PluginPushNotifications.didRegister(deviceToken: Data([0xAB, 0x01, 0xFF]))
        let current = PushTokenRegistry.shared.current()
        XCTAssertEqual(current?.token, "ab01ff")
        XCTAssertEqual(current?.provider, PluginPushNotifications.providerApns)
    }

    func testProviderExchangesApnsTokenForFcmToken() {
        let fake = FakeProvider()
        PluginPushNotifications.tokenProvider = fake
        PluginPushNotifications.didRegister(deviceToken: Data([0x01, 0x02]))
        XCTAssertEqual(fake.received, [Data([0x01, 0x02])])
        let current = PushTokenRegistry.shared.current()
        XCTAssertEqual(current?.token, "fcm-token-1")
        XCTAssertEqual(current?.provider, 1)
    }

    func testProviderFailureNeverReportsTheApnsTokenAsFcm() {
        let fake = FakeProvider()
        fake.result = .failure(Failure())
        PluginPushNotifications.tokenProvider = fake
        PluginPushNotifications.didRegister(deviceToken: Data([0x01]))
        XCTAssertNil(PushTokenRegistry.shared.current())

        fake.result = .success("")
        PluginPushNotifications.didRegister(deviceToken: Data([0x01]))
        XCTAssertNil(PushTokenRegistry.shared.current())
    }

    func testRefreshReplacesTheCachedToken() {
        PluginPushNotifications.tokenProvider = FakeProvider()
        PluginPushNotifications.didRegister(deviceToken: Data([0x01]))
        PluginPushNotifications.didRefresh(token: "fcm-token-2", provider: PluginPushNotifications.providerFcm)
        XCTAssertEqual(PushTokenRegistry.shared.current()?.token, "fcm-token-2")
        PluginPushNotifications.didRefresh(token: "", provider: PluginPushNotifications.providerFcm)
        XCTAssertEqual(PushTokenRegistry.shared.current()?.token, "fcm-token-2")
    }

    func testInstallingAProviderDropsATokenOfAnotherProvider() {
        PluginPushNotifications.tokenProvider = nil
        PluginPushNotifications.didRegister(deviceToken: Data([0x0A]))
        XCTAssertNotNil(PushTokenRegistry.shared.current())
        PluginPushNotifications.tokenProvider = FakeProvider()
        XCTAssertNil(PushTokenRegistry.shared.current())
    }

    func testPayloadCarriesTheProvider() throws {
        let values = try WireMap.decode(PushTokenRegistry.shared.payload("t", provider: 1))
        XCTAssertEqual(values["token"], .text("t"))
        XCTAssertEqual(values["provider"], .integer(1))
    }
}
