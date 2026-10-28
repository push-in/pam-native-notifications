<?php

declare(strict_types=1);

$autoload = getenv('PAM_TEST_AUTOLOAD') ?: dirname(__DIR__).'/vendor/autoload.php';
require $autoload;

final class TestDiagnostics
{
    public static array $messages = [];
    public static ?array $moduleCall = null;
    public static ?array $typedCall = null;
}

if (!function_exists('pam_native_error')) {
    function pam_native_error(string $message): void { TestDiagnostics::$messages[] = $message; }
}
if (!function_exists('pam_native_call')) {
    function pam_native_call(int $requestId, string $module, string $method, string $payload): void
    {
        TestDiagnostics::$moduleCall = compact('requestId', 'module', 'method', 'payload');
    }
}
if (!function_exists('pam_native_call_typed')) {
    function pam_native_call_typed(int $requestId, int $operation, string $payload): void
    {
        TestDiagnostics::$typedCall = compact('requestId', 'operation', 'payload');
    }
}

$assertions = 0;
$assert = static function (bool $condition, string $message) use (&$assertions): void {
    ++$assertions;
    if (!$condition) throw new RuntimeException($message);
};
