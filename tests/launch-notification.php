<?php

declare(strict_types=1);

use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Notifications\PushEventType;
use Pam\Native\Notifications\PushMessage;
use Pam\Native\Notifications\PushNotifications;

// PushNotifications::takeLaunchNotification(): the host exports the tap that
// cold-started the process (base64 of the nextPushEvent wire map plus a
// `launch` token) before PHP boots; PHP reads it once and the queue never
// delivers it again.

$launchCall = static function (): array {
    $call = TestDiagnostics::$moduleCall ?? throw new RuntimeException('No native module call was recorded.');

    return $call + ['values' => $call['payload'] === '' ? [] : Wire::decodeMap($call['payload'])];
};
$launchWire = static fn (string $token, array $overrides = []): string => Wire::map($overrides + [
    'event' => PushEventType::Opened->value,
    'id' => 'chat-42',
    'title' => 'Ana',
    'body' => 'Chegou?',
    'data' => '{"chat_id":"42","type":"message"}',
    'deepLink' => 'zechat://chat/42',
    'rendered' => false,
    'launch' => $token,
]);
$exportLaunch = static function (?string $value): void {
    putenv($value === null ? PushNotifications::LAUNCH_ENV : PushNotifications::LAUNCH_ENV.'='.$value);
};

// No launch tap: null, and nothing is sent to the native side.
PushNotifications::resetRuntime();
$exportLaunch(null);
TestDiagnostics::$moduleCall = null;
$assert(
    PushNotifications::takeLaunchNotification() === null && TestDiagnostics::$moduleCall === null,
    'A cold start without a notification tap must not report a launch notification.',
);

// Exported tap: typed Opened message, consumed natively, returned only once.
PushNotifications::resetRuntime();
$exportLaunch(base64_encode($launchWire('launch-a')));
TestDiagnostics::$moduleCall = null;
$launch = PushNotifications::takeLaunchNotification();
$assert(
    $launch instanceof PushMessage
        && $launch->event === PushEventType::Opened
        && $launch->id === 'chat-42'
        && $launch->title === 'Ana'
        && $launch->body === 'Chegou?'
        && $launch->data === ['chat_id' => '42', 'type' => 'message']
        && $launch->deepLink === 'zechat://chat/42'
        && !$launch->rendered,
    'The launch notification must be decoded synchronously as an Opened PushMessage.',
);
$consume = $launchCall();
$assert(
    $consume['module'] === 'notifications'
        && $consume['method'] === 'consumeLaunchNotification'
        && $consume['values'] === ['launch' => 'launch-a'],
    'Taking the launch notification must drop its queued copy natively.',
);
Runtime::dispatchModuleResult($consume['requestId'], ModuleResultStatus::Success->value, '');
TestDiagnostics::$moduleCall = null;
$assert(
    PushNotifications::takeLaunchNotification() === null && TestDiagnostics::$moduleCall === null,
    'The launch notification must be returned once per process even if the host still exports it.',
);

// The queue answered before the consume reached native: the listener skips
// that copy and keeps listening.
$opened = [];
PushNotifications::listen(static function (PushMessage $message) use (&$opened): void { $opened[] = $message; });
$wait = $launchCall();
$assert($wait['method'] === 'nextPushEvent', 'listen() must long-poll the native push queue.');
Runtime::dispatchModuleResult($wait['requestId'], ModuleResultStatus::Success->value, $launchWire('launch-a'));
$rearmed = $launchCall();
$assert(
    $opened === [] && $rearmed['method'] === 'nextPushEvent' && $rearmed['requestId'] !== $wait['requestId'],
    'The queue must not deliver the launch notification again after takeLaunchNotification().',
);
Runtime::dispatchModuleResult($rearmed['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'event' => PushEventType::Received->value, 'id' => 'push-7', 'title' => 'Bia', 'body' => 'Oi',
    'data' => '{}', 'deepLink' => '', 'rendered' => true,
]));
$assert(
    count($opened) === 1 && $opened[0]->event === PushEventType::Received && $opened[0]->id === 'push-7'
        && $opened[0]->rendered && $opened[0]->deepLink === null,
    'Other push events must still reach the listener after the launch copy is skipped.',
);
Runtime::dispatchModuleResult($launchCall()['requestId'], ModuleResultStatus::Success->value, $launchWire('launch-b'));
$assert(
    count($opened) === 2 && $opened[1]->event === PushEventType::Opened,
    'A queued launch tap from another process (other token) must still be delivered.',
);

// Listener first: it receives the tap, and takeLaunchNotification() then
// returns null even while the host has not cleared the export yet.
PushNotifications::resetRuntime();
$exportLaunch(base64_encode($launchWire('launch-c')));
$opened = [];
PushNotifications::listen(static function (PushMessage $message) use (&$opened): void { $opened[] = $message; });
Runtime::dispatchModuleResult($launchCall()['requestId'], ModuleResultStatus::Success->value, $launchWire('launch-c'));
TestDiagnostics::$moduleCall = null;
$assert(
    count($opened) === 1 && $opened[0]->id === 'chat-42'
        && PushNotifications::takeLaunchNotification() === null
        && TestDiagnostics::$moduleCall === null,
    'A launch tap already delivered by the listener must not be returned by takeLaunchNotification().',
);

// Malformed or foreign exports are ignored without throwing.
foreach ([
    'not base64 !',
    base64_encode('garbage'),
    base64_encode($launchWire('')),
    base64_encode($launchWire('launch-d', ['event' => PushEventType::Received->value])),
    base64_encode($launchWire('launch-e', ['data' => '{broken'])),
] as $malformed) {
    PushNotifications::resetRuntime();
    $exportLaunch($malformed);
    TestDiagnostics::$moduleCall = null;
    $assert(
        PushNotifications::takeLaunchNotification() === null && TestDiagnostics::$moduleCall === null,
        'A malformed launch export must be ignored.',
    );
}

$exportLaunch(null);
PushNotifications::resetRuntime();
