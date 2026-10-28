<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

enum PushProvider: int
{
    case Fcm = 1;
    case Apns = 2;
}
