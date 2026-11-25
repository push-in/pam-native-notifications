<?php

declare(strict_types=1);

use Pam\Native\Notifications\Contracts\PushTokenRegistration;

$registrationEndpoint = \Pam\Native\Notifications\Contracts\ActionEndpoint::post('https://api.example.test/tokens')
    ->bearerFromCredential('account')->json(['token' => '{token}', 'account' => '{account}', 'provider_type' => 2]);
$configured = false;
$request = PushTokenRegistration::configure($registrationEndpoint, [' B ', 'a', 'b'], static function () use (&$configured): void { $configured = true; });
$call = $lastCall();
$assert($call['method'] === 'configureTokenRegistration', 'Token registration uses the additive native method.');
$assert(json_decode($call['values']['accounts'], true, flags: JSON_THROW_ON_ERROR) === ['a', 'b'], 'Account IDs are normalized, deduplicated and ordered.');
$endpoint = json_decode($call['values']['endpoint'], true, flags: JSON_THROW_ON_ERROR);
$assert($endpoint['headers']['Authorization'] === 'Bearer {credential:account}', 'Configuration carries a credential reference, never a bearer.');
$assert($endpoint['body'] === ['token' => '{token}', 'account' => '{account}', 'provider_type' => 2], 'Application endpoint fields and integer provider values stay unchanged.');
\Pam\Native\Internal\Runtime::dispatchModuleResult($request, \Pam\Native\ModuleResultStatus::Success->value, '');
$assert($configured, 'Native success invokes the configure callback (also iOS no-op).');
$failed = null;
$request = PushTokenRegistration::configure($registrationEndpoint, [], failure: static function (string $message) use (&$failed): void { $failed = $message; });
$assert($lastCall()['values']['accounts'] === '[]', 'An empty account list explicitly disables background delivery.');
\Pam\Native\Internal\Runtime::dispatchModuleResult($request, \Pam\Native\ModuleResultStatus::Failure->value, 'Persistence failed');
$assert($failed === 'Persistence failed', 'Native configuration failure is surfaced.');
foreach ([array_fill(0, 65, 'a'), ['named' => 'a'], [''], [str_repeat('a', 129)], [123]] as $invalidAccounts) {
    $assert($rejects(static fn () => PushTokenRegistration::configure($registrationEndpoint, $invalidAccounts)), 'Invalid account list must be rejected before native work.');
}
$assert($rejects(static fn () => PushTokenRegistration::configure(\Pam\Native\Notifications\Contracts\ActionEndpoint::post('https://api.example.test')->bearer('literal'), ['a'])), 'A literal bearer must not be persisted in registration configuration.');
$assert($rejects(static fn () => PushTokenRegistration::configure(\Pam\Native\Notifications\Contracts\ActionEndpoint::post('https://api.example.test')->bearerFromCredential('other'), ['a'])), 'Queue credentials must be scoped to its account variable.');
$assert($rejects(static fn () => PushTokenRegistration::configure(\Pam\Native\Notifications\Contracts\ActionEndpoint::post('https://api.example.test')->bearerFromCredential('account')->json(['large' => str_repeat('x', 65_536)]), ['a'])), 'Configuration must be size bounded.');
