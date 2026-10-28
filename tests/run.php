<?php

declare(strict_types=1);

require __DIR__.'/bootstrap.php';
$provider = new \Pam\Native\Notifications\NotificationsPluginProvider();
$provider->register();
$provider->boot();
require __DIR__.'/core-compat.php';
echo "{$assertions} assertions, 0 failures\n";
