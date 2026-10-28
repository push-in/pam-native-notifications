<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use Pam\Native\Plugin\PluginProvider;

final class NotificationsPluginProvider implements PluginProvider
{
    public function register(): void
    {
    }

    public function boot(): void
    {
        \Pam\Native\Plugin\RuntimeHooks::onRouteFocused(self::class, Contracts\PushRendering::routeFocused(...));
        \Pam\Native\Plugin\RuntimeHooks::onReset(self::class, PushNotifications::resetRuntime(...));
    }
}
