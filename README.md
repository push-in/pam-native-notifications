# Native notifications


## Core extraction (1.46)

Requires `pushinbr/pam-native` ^1.46. Install with `composer require pushinbr/pam-native-notifications`.
The core keeps deprecated PHP aliases through 1.46; they are removed in 1.47.
Native wire contracts are retained. Swift compilation and device QA remain pending.

## Presentation of rendered pushes

```php
PushRendering::forType('4')
    ->notification('{notification_title}', '{notification_body}')
    ->largeIcon('{actor_avatar_url}')        // circle avatar
    ->image('{post_thumbnail_url}')          // BigPictureStyle when it loads
    ->color('#20B8BD')                       // small icon, actions, lights
    ->channel('messages', 'Messages', description: 'Messages and activity', vibrate: true, lights: true)
    ->suppressInForeground()                 // only while the app is not visible
    ->register();
```

Android fixes a channel's settings when it is first created, so the channel
options only reach new installs (or a new channel id). The Android small icon
is the host drawable `pam_notification_icon` when present, else the app's
`android.icon.monochrome` layer cropped to its central 88 dp, else `pam_icon`.

