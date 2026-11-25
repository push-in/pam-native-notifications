# Changelog

## 0.1.14 - 2026-11-25

- Android: opt-in native FCM token registration through an application-defined
  endpoint, without an Activity or PHP runtime. Account credentials remain in
  the existing encrypted store.
- Persist the latest token, deduplicate per account and credential, and use a
  one-shot network-constrained job with eight bounded attempts. Logout, token
  rotation and credential changes invalidate stale work. No polling or added
  native dependency.
- Keep registration and receiver persistence off the main thread. Reject stale
  Firebase callbacks before they can replace a fresh queued token after logout.
- Bound notification endpoint error-response reads to 16 KiB.
- Add PHP, state-machine and Android integration regression coverage.

Requires PAM Native 1.54.16 for the new native token event. Existing notification
APIs remain compatible; iOS accepts the configuration as a no-op.

## 0.1.13 - 2026-11-19

- Fixed: Android below 13: Notifications::requestPermission() reports areNotificationsEnabled() instead of always true (false when the user switched notifications off).

## 0.1.12 - 2026-11-18

- Added: PushRenderingRule::openAction() (foreground button: deep link, optional native endpoint, extra data, dismiss).
- Fixed: conversation rules now render their action() buttons on Android.
- Added: alertOnce() (default on for conversations and keyed rules), key() and subText() on push rendering rules, ConversationNotification::alertOnce().
- Added: suppressInForeground(where:, blank:) conditions.
- Added: PushNotifications::persistRenderedEvents().
- Fixed: a dismissed conversation no longer brings back its old history.
- Changed: avatar downloads are written to pam-images-v1 and memoized, with 1.5 s / 2 s timeouts.
- Changed: rendered push events are no longer written to disk.

## 0.1.11 - 2026-11-18

- `PushRenderingRule::color('#RRGGBB')`: Android accent of the small icon,
  the actions and the channel lights.
- `PushRenderingRule::largeIcon('{avatar_url}')` (conversation and standard
  rules) and `image('{thumbnail_url}')` (standard rules, `BigPictureStyle`;
  big text without it). HTTPS or private-file templates; failures are skipped.
- `channel($id, $name, description:, vibrate:, lights:)`: options applied
  when Android creates the channel.
- `suppressInForeground()`: the rule never notifies while the app is in the
  foreground (Android and iOS rendering).
- Android small icon: a host drawable `pam_notification_icon` wins; otherwise
  the app's adaptive-icon monochrome layer, cropped to its central 88 dp,
  replaces the generic `pam_icon` glyph.

## 0.1.10 - 2026-11-18

- Android 7.x: conversation notifications use the default sound and vibration for importance 2 and above, as the API 26+ channels do (8.0+ ignores builder defaults, so nothing changes there).
- Android lint is clean at minSdk 24 (`NewApi` included): `SharedPreferences` edits go through the KTX `edit {}` (same apply/commit), and the `POST_NOTIFICATIONS` and host-icon lookups carry justified suppressions.
- Added a standalone Gradle harness (`android/settings.gradle.kts`, `android/build.gradle.kts`) for lint, the unit tests and the instrumented tests; local notifications, conversations, inline replies and push rendering pass on API 24 and API 35 emulators. PAM applications ignore it.

## 0.1.9 - 2026-11-17

- Android 7.0/7.1 support: the plugin declares minSdk 24 and every newer Android API it uses has a version check and fallback. Requires PAM Native 1.53.1 or later.

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

