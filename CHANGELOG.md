# Changelog

## 0.1.8 - 2026-11-16

- Fixes push registration in R8 release builds: `registerPush` /
  `unregisterPush` reach Firebase Messaging by reflection (it stays an
  optional host dependency), and R8 renamed `FirebaseMessaging.getToken()`,
  `deleteToken()` and the `Task` members, so no token ever reached the app.
  The new `android/consumer-rules.pro` (`android.consumerRules`) keeps them.
- Native failures now name their cause (Firebase missing, a stripped member,
  Firebase's own `Task` exception, the 15 s timeout) and the task is polled
  every 100 ms instead of 50 ms.
- `PushNotifications::register()` / `unregister()` log every native failure
  with `error_log`, also when the caller passes a `$failure` callback.

## 0.1.7 - 2026-11-16

- Republishes 0.1.6: Packagist kept the `v0.1.6` tag on the 0.1.5 commit
  after it was re-pointed, so `^0.1.6` installed code without
  `takeLaunchNotification()`. No code change; require `^0.1.7`.

## 0.1.6 - 2026-11-16

- `PushNotifications::takeLaunchNotification(): ?PushMessage` returns, once
  per process, the notification tap that cold-started the app (an `Opened`
  `PushMessage`), synchronously, so the first frame can already show its
  screen. The host exports it before PHP boots in
  `PAM_NATIVE_NOTIFICATIONS_LAUNCH` (base64 of the `nextPushEvent` wire map
  plus a per-launch `launch` token), like `Linking::takeLaunchUrl()`.
- The same tap is delivered exactly once: when PHP takes it, the new native
  method `consumeLaunchNotification` drops the queued copy and clears the
  export; when `listen()` / `listenAndRoute()` receive it first,
  `takeLaunchNotification()` returns null. Apps that only listen keep the
  previous behaviour.
- Android: `HostEvents.initialIntent` (before PHP boots) captures taps on the
  plugin's notifications (`pam.notification.*` extras). Normal launches never
  touch the environment.
- iOS: `PluginPushNotifications.didLaunch(response:)` /
  `didLaunch(remoteNotification:)` capture the launch tap; the delegate's
  later copy of the same tap is not queued again. `launchOptions[.remoteNotification]`
  is read automatically through `UIApplication.didFinishLaunchingNotification`;
  scene-based hosts need the core to forward
  `UIScene.ConnectionOptions.notificationResponse` before PHP boots (see the
  README). Until then `takeLaunchNotification()` returns null there and the
  tap still arrives through `listen()`.

## 0.1.5 - 2026-11-08

- Every `v*` publication tag is certified by the PAM Native ecosystem gate
  (`push-in/pam` `ecosystem-compatibility.yml`). No code changes.

## 0.1.4 - 2026-10-28

- Android: the plugin compiles as its own Gradle module. Conversation avatars
  from `pam-file:///` paths resolve with the plugin's own private-path helper;
  the core's `resolvePrivateFile` is internal to the app module, so 0.1.2 and
  0.1.3 failed to build in a real app.

## 0.1.3 - 2026-10-28

- iOS: compiles as its own Swift module against pam-native 1.46.0, which
  makes the media disk cache and the private file path resolver public
  (conversation avatars). The capability tests import the core with
  `@testable`, since they also cover core helpers (multipart body, progress
  throttle, screen shield, window module). No change to the plugin code.

## 0.1.2

- Preserve the host notification glyph through resource lookup, without linking the host R class.

## 0.1.1

- Bound background push rendering to one worker and 64 queued messages; release idle workers.

## 0.1.0

- Owns the optional native notifications capability extracted from the 1.46 core.
- Preserves PHP aliases and native contracts during migration.

