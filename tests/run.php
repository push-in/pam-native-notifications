<?php

declare(strict_types=1);

require __DIR__.'/bootstrap.php';
$provider = new \Pam\Native\Notifications\NotificationsPluginProvider();
$provider->register();
$provider->boot();
require __DIR__.'/core-compat.php';
require __DIR__.'/launch-notification.php';
require __DIR__.'/push-token-registration.php';
echo "{$assertions} assertions, 0 failures\n";
