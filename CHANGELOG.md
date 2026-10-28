# Changelog

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

