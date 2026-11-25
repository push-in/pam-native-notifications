<?php

declare(strict_types=1);

namespace Pam\Native\Notifications\Contracts;

use Closure;
use InvalidArgumentException;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

/** Android delivery of the latest FCM token while PHP is stopped. iOS is a no-op. */
final class PushTokenRegistration
{
    private function __construct()
    {
    }

    /**
     * Configure after NotificationCredentials::sync succeeds. Empty accounts
     * disable delivery. Only {token}/{account} are supplied by this queue;
     * provider and other application fields stay in the endpoint's JSON body.
     *
     * @param list<string> $accounts Signed-in account IDs, never bearer tokens.
     * @param null|Closure(): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function configure(ActionEndpoint $endpoint, array $accounts, ?Closure $callback = null, ?Closure $failure = null): int
    {
        if (!array_is_list($accounts) || count($accounts) > 64) {
            throw new InvalidArgumentException('Token registration supports a list of at most 64 accounts.');
        }
        $normalized = [];
        foreach ($accounts as $account) {
            if (!is_string($account)) {
                throw new InvalidArgumentException('Token registration account IDs must be strings.');
            }
            $normalized[] = NotificationCredentials::account($account);
        }
        $normalized = array_values(array_unique($normalized));
        sort($normalized, SORT_STRING);
        $config = $endpoint->toArray();
        $headers = (array) $config['headers'];
        $authorization = array_filter($headers, static fn (string $name): bool => strtolower($name) === 'authorization', ARRAY_FILTER_USE_KEY);
        if (count($authorization) !== 1 || reset($authorization) !== 'Bearer {credential:account}') {
            throw new InvalidArgumentException("Token registration requires bearerFromCredential('account').");
        }
        $json = json_encode($config, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
        if (strlen($json) > 65_536) {
            throw new InvalidArgumentException('Token registration endpoints are limited to 65536 bytes.');
        }

        return NativeModules::call('notifications', 'configureTokenRegistration', [
            'endpoint' => $json,
            'accounts' => json_encode($normalized, JSON_THROW_ON_ERROR),
        ], static function ($result) use ($callback, $failure): void {
            if ($result->status === ModuleResultStatus::Failure) {
                if ($failure !== null) {
                    $failure($result->payload);
                    return;
                }
                throw new RuntimeException($result->payload);
            }
            $callback?->__invoke();
        });
    }
}
