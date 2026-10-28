<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

enum PushEventType: int
{
    case Received = 1;
    case Opened = 2;
}
