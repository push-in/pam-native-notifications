<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\Notifications\PushToken;
use Pam\Native\Notifications\PushProvider;
use Pam\Native\Notifications\PushEventType;
use Pam\Native\Notifications\PushMessage;
use Pam\Native\Navigation\Navigator;
use RuntimeException;
use LogicException;
use Throwable;

use function base64_decode;
use function getenv;
use function is_array;
use function is_string;

final class PushNotifications
{
    /**
     * Exported by the native host before PHP boots when the process was
     * started by a notification tap: base64 of the same wire map the
     * `nextPushEvent` queue delivers, plus a per-launch `launch` token.
     */
    public const string LAUNCH_ENV = 'PAM_NATIVE_NOTIFICATIONS_LAUNCH';

    private static int $nextSubscription = 1;

    private static bool $launchRead = false;

    /** Token of the launch notification already handed to PHP (take or listener). */
    private static ?string $launchToken = null;

    /** @var array<int, Closure(PushMessage): void> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /**
     * @param Closure(PushToken): void $callback
     * @param Closure(string): void|null $failure
     */
    public static function register(
        Closure $callback,
        ?Closure $failure = null,
    ): int
    {
        return NativeModules::call(
            'notifications',
            'registerPush',
            [],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $callback(new PushToken(
                    value: (string) ($values['token'] ?? ''),
                    provider: PushProvider::from((int) ($values['provider'] ?? 0)),
                ));
            },
        );
    }

    /**
     * Stops remote notifications for this installation and invalidates its native token.
     *
     * @param Closure(): void $callback
     * @param Closure(string): void|null $failure
     */
    public static function unregister(
        Closure $callback,
        ?Closure $failure = null,
    ): int {
        return NativeModules::call(
            'notifications',
            'unregisterPush',
            [],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $callback();
            },
        );
    }

    /** @param Closure(PushMessage): void $callback */
    public static function listen(Closure $callback): int
    {
        if (self::$subscriptions !== []) {
            throw new LogicException('Only one PushNotifications listener may be active.');
        }
        $subscription = self::$nextSubscription++;
        self::$subscriptions[$subscription] = $callback;
        self::arm($subscription);
        return $subscription;
    }

    public static function unsubscribe(int $subscription): void
    {
        unset(self::$subscriptions[$subscription]);
    }

    /**
     * The notification whose tap started this process (an `Opened`
     * PushMessage), read synchronously so the first frame can already show
     * its screen: the host exports it before PHP boots, like
     * `Linking::takeLaunchUrl()`.
     *
     * Returns it once per process; later calls, hot reloads and crash
     * recoveries return null. The same tap is never delivered again by
     * `listen()` / `listenAndRoute()`; when a listener already received it
     * (the listener was armed first and the native queue answered), this
     * returns null instead.
     */
    public static function takeLaunchNotification(): ?PushMessage
    {
        if (self::$launchRead) {
            return null;
        }
        self::$launchRead = true;
        $encoded = getenv(self::LAUNCH_ENV);
        if (!is_string($encoded) || $encoded === '') {
            return null;
        }
        $payload = base64_decode($encoded, true);
        if ($payload === false || $payload === '') {
            return null;
        }
        try {
            $values = Wire::decodeMap($payload);
            $token = (string) ($values['launch'] ?? '');
            if ($token === '' || $token === self::$launchToken) {
                return null;
            }
            $message = self::message($values);
        } catch (Throwable) {
            return null;
        }
        if ($message->event !== PushEventType::Opened) {
            return null;
        }
        self::$launchToken = $token;
        // Drops the queued copy natively and clears the exported value, so
        // neither the queue nor a later PHP request delivers this tap again.
        NativeModules::call(
            'notifications',
            'consumeLaunchNotification',
            ['launch' => $token],
            static function (): void {
            },
        );

        return $message;
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$subscriptions = [];
        self::$nextSubscription = 1;
        self::$launchRead = false;
        self::$launchToken = null;
    }

    /** @param null|Closure(PushMessage): void $callback */
    public static function listenAndRoute(
        Navigator $navigator,
        ?Closure $callback = null,
    ): int {
        return self::listen(static function (PushMessage $message) use ($navigator, $callback): void {
            if ($message->event === PushEventType::Opened && $message->deepLink !== null) {
                $navigator->open($message->deepLink);
            }
            $callback?->__invoke($message);
        });
    }

    private static function arm(int $subscription): void
    {
        if (!isset(self::$subscriptions[$subscription])) {
            return;
        }
        NativeModules::call('notifications', 'nextPushEvent', [], static function ($result) use ($subscription): void {
            if (!isset(self::$subscriptions[$subscription])) {
                return;
            }
            if ($result->status === ModuleResultStatus::Failure) {
                unset(self::$subscriptions[$subscription]);
                throw new RuntimeException($result->payload);
            }
            $values = Wire::decodeMap($result->payload);
            $launch = (string) ($values['launch'] ?? '');
            if ($launch !== '') {
                if ($launch === self::$launchToken) {
                    // takeLaunchNotification() already returned this tap.
                    self::arm($subscription);

                    return;
                }
                self::$launchToken = $launch;
            }
            $message = self::message($values);
            self::$subscriptions[$subscription]($message);
            self::arm($subscription);
        });
    }

    /** @param array<string, string|int|float|bool> $values */
    private static function message(array $values): PushMessage
    {
        $data = json_decode((string) ($values['data'] ?? '{}'), true, flags: JSON_THROW_ON_ERROR);

        return new PushMessage(
            event: PushEventType::from((int) ($values['event'] ?? 1)),
            id: (string) ($values['id'] ?? ''),
            title: (string) ($values['title'] ?? ''),
            body: (string) ($values['body'] ?? ''),
            data: is_array($data) ? $data : [],
            deepLink: ($values['deepLink'] ?? '') !== '' ? (string) $values['deepLink'] : null,
            rendered: (bool) ($values['rendered'] ?? false),
        );
    }
}
