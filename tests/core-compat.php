<?php
declare(strict_types=1);
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\Navigation\Navigator;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Notifications\PushNotifications;
use Pam\Native\UI\Button;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

use Pam\Native\Navigation\DeepLink;
use Pam\Native\Notifications\Contracts\ActionEndpoint;
use Pam\Native\Notifications\Contracts\NotificationAction;
use Pam\Native\Notifications\Contracts\NotificationActionType;
use Pam\Native\Notifications\Contracts\NotificationCredentials;
use Pam\Native\Notifications\Contracts\Person;
use Pam\Native\Notifications\Contracts\PushRendering;
use Pam\Native\Notifications\Contracts\ReplyFailures;
use Pam\Native\Notifications\NotificationImportance;
use Pam\Native\Notifications\Notifications;

$lastCall = static function (): array {
    $call = TestDiagnostics::$moduleCall;
    if ($call === null) {
        throw new RuntimeException('No native module call was recorded.');
    }

    return $call + ['values' => $call['payload'] === '' ? [] : Wire::decodeMap($call['payload'])];
};
$rejects = static function (Closure $operation): bool {
    try {
        $operation();
    } catch (InvalidArgumentException|RuntimeException|LogicException) {
        return true;
    }

    return false;
};


foreach ([true, false] as $notificationGranted) {
    $notificationDecision = null;
    $permissionRequest = \Pam\Native\Notifications\Notifications::requestPermission(
        static function (bool $granted) use (&$notificationDecision): void { $notificationDecision = $granted; },
        failure: static function (string $message): void { throw new RuntimeException('A permission decision is not a failure.'); },
    );
    Runtime::dispatchModuleResult($permissionRequest, ModuleResultStatus::Success->value, Wire::map(['granted' => $notificationGranted]));
    $assert($notificationDecision === $notificationGranted, 'Notification permission must preserve granted and denied decisions.');
}
$notificationFailure = null;
$permissionRequest = \Pam\Native\Notifications\Notifications::requestPermission(
    static function (bool $granted): void { throw new RuntimeException('Native failure must not invoke permission success.'); },
    failure: static function (string $message) use (&$notificationFailure): void { $notificationFailure = $message; },
);
Runtime::dispatchModuleResult($permissionRequest, ModuleResultStatus::Failure->value, 'No foreground activity.');
$assert($notificationFailure === 'No foreground activity.', 'Notification permission failure must reach the optional callback.');
$permissionRequest = \Pam\Native\Notifications\Notifications::requestPermission(static function (bool $granted): void {});
$permissionErrorsBefore = count(TestDiagnostics::$messages);
Runtime::dispatchModuleResult($permissionRequest, ModuleResultStatus::Failure->value, 'Permission module failed.');
$assert(count(TestDiagnostics::$messages) === $permissionErrorsBefore + 1
    && str_contains(TestDiagnostics::$messages[array_key_last(TestDiagnostics::$messages)], 'Permission module failed.'),
    'Omitting the permission failure callback must preserve legacy error reporting.');
array_pop(TestDiagnostics::$messages);

$pushRegistrationFailure = null;
$pushRegistrationRequest = PushNotifications::register(
    static function (): void {
        throw new RuntimeException('Failed push registration must not invoke success.');
    },
    static function (string $message) use (&$pushRegistrationFailure): void {
        $pushRegistrationFailure = $message;
    },
);
$pushRegistrationCall = TestDiagnostics::$moduleCall;
$assert(
    $pushRegistrationCall !== null
        && $pushRegistrationCall['requestId'] === $pushRegistrationRequest
        && $pushRegistrationCall['module'] === 'notifications'
        && $pushRegistrationCall['method'] === 'registerPush',
    'Push registration must emit its typed native module call.',
);
Runtime::dispatchModuleResult(
    $pushRegistrationRequest,
    ModuleResultStatus::Failure->value,
    'Firebase rejected push token registration.',
);
$assert(
    $pushRegistrationFailure === 'Firebase rejected push token registration.',
    'Push registration failures must reach the optional failure callback.',
);

$pushUnregistered = false;
$pushUnregisterRequest = PushNotifications::unregister(
    static function () use (&$pushUnregistered): void { $pushUnregistered = true; },
    static function (string $message): void { throw new RuntimeException($message); },
);
$pushUnregisterCall = TestDiagnostics::$moduleCall;
$assert(
    $pushUnregisterCall !== null
        && $pushUnregisterCall['requestId'] === $pushUnregisterRequest
        && $pushUnregisterCall['module'] === 'notifications'
        && $pushUnregisterCall['method'] === 'unregisterPush',
    'Push unregistration must emit its typed native module call.',
);
Runtime::dispatchModuleResult($pushUnregisterRequest, ModuleResultStatus::Success->value, '');
$assert($pushUnregistered, 'Push unregistration must invoke its success callback.');

$pushUnregisterFailure = null;
$failedPushUnregisterRequest = PushNotifications::unregister(
    static function (): void { throw new RuntimeException('Failed push unregistration must not invoke success.'); },
    static function (string $message) use (&$pushUnregisterFailure): void { $pushUnregisterFailure = $message; },
);
Runtime::dispatchModuleResult(
    $failedPushUnregisterRequest,
    ModuleResultStatus::Failure->value,
    'Firebase rejected push token unregistration.',
);
$assert(
    $pushUnregisterFailure === 'Firebase rejected push token unregistration.',
    'Push unregistration failures must reach the optional failure callback.',
);



// 9. Conversation notifications, actions and declarative push rendering.
Notifications::conversation('chat:42')
    ->title('Viagem')
    ->group()
    ->self(Person::make('Você'))
    ->message(Person::make('Ana', 'https://cdn.example.test/ana.jpg', 'user-1'), 'Partiu?', 1_700_000_000_000, 'm1')
    ->ownMessage('Bora', 1_700_000_001_000)
    ->reply('Responder', ActionEndpoint::post('https://api.example.test/chats/{chat_id}/messages')->bearerFromStorage('auth.token')->json(['body' => '{reply}']))
    ->markRead('Marcar como lida')
    ->deepLink('example://chat/42')
    ->data(['chat_id' => '42'])
    ->importance(NotificationImportance::Urgent)
    ->show();
$conversation = $lastCall();
$spec = json_decode((string) $conversation['values']['spec'], true);
$assert(
    $conversation['method'] === 'showConversation'
        && $spec['key'] === 'chat:42' && $spec['group'] === true && $spec['self']['name'] === 'Você'
        && $spec['messages'][0] === ['id' => 'm1', 'text' => 'Partiu?', 'timestamp' => 1_700_000_000_000, 'sender' => ['name' => 'Ana', 'avatar' => 'https://cdn.example.test/ana.jpg', 'key' => 'user-1']]
        && !isset($spec['messages'][1]['sender'])
        && $spec['replyLabel'] === 'Responder' && $spec['markReadLabel'] === 'Marcar como lida'
        && $spec['replyEndpoint']['headers']['Authorization'] === 'Bearer {storage:auth.token}'
        && $spec['replyEndpoint']['body'] === ['body' => '{reply}']
        && $spec['markReadEndpoint'] === null
        && $spec['data'] === '{"chat_id":"42"}' && $spec['importance'] === 4,
    'Conversation notifications must serialize MessagingStyle messages, actions and endpoints.',
);
Notifications::cancelGroup('chat:42');
$assert($lastCall()['method'] === 'cancelConversation' && $lastCall()['values'] == ['key' => 'chat:42'], 'cancelGroup must cancel the conversation natively.');
$assert(
    $rejects(static fn () => Notifications::conversation('bad key'))
        && $rejects(static fn () => Notifications::conversation('chat:1')->show())
        && $rejects(static fn () => ActionEndpoint::post('ftp://x'))
        && $rejects(static fn () => Person::make('')),
    'Conversation notifications must validate keys, messages and endpoints.',
);

$actions = [];
Notifications::onAction(static function (NotificationAction $action) use (&$actions): void { $actions[] = $action; });
$actionCall = $lastCall();
$assert($actionCall['method'] === 'nextAction', 'Notification actions must long-poll the native queue.');
Runtime::dispatchModuleResult($actionCall['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'type' => 1, 'conversation' => 'chat:42', 'text' => 'Já vou', 'data' => '{"chat_id":"42"}', 'deepLink' => '',
    'handledNatively' => true, 'statusCode' => 201, 'timestamp' => 5,
]));
$assert(
    count($actions) === 1 && $actions[0]->type === NotificationActionType::Reply && $actions[0]->isReply()
        && $actions[0]->text === 'Já vou' && $actions[0]->data === ['chat_id' => '42'] && $actions[0]->delivered()
        && $lastCall()['method'] === 'nextAction',
    'Inline replies must reach PHP typed and re-arm the listener.',
);

Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'type' => 1, 'conversation' => 'chat:43', 'text' => 'Oi', 'data' => '{"chat_id":"43","user_id":"u-b"}', 'deepLink' => '',
    'handledNatively' => false, 'statusCode' => 0, 'timestamp' => 6, 'credentialMissing' => true,
]));
$assert(
    count($actions) === 2 && $actions[1]->credentialMissing && !$actions[1]->delivered() && !$actions[0]->credentialMissing,
    'Actions must report when the push account had no native credential.',
);
$pendingActionCall = $lastCall();

// 9b. Per-account notification credentials.
$credentialEndpoint = ActionEndpoint::post('https://api.example.test/chats/{chat_id}/messages')
    ->bearerFromCredential('user_id', 'recipient_user_id')
    ->json(['body' => '{reply}'])
    ->toArray();
$assert(
    $credentialEndpoint['headers']->Authorization === 'Bearer {credential:user_id|recipient_user_id}',
    'bearerFromCredential must resolve the token of the push account natively.',
);
$assert(
    $rejects(static fn () => ActionEndpoint::post('https://x.test/{credential:user_id}'))
        && $rejects(static fn () => ActionEndpoint::post('https://x.test')->json(['token' => '{credential:user_id}']))
        && $rejects(static fn () => ActionEndpoint::post('https://x.test')->bearerFromCredential('bad field'))
        && $rejects(static fn () => ActionEndpoint::post('https://x.test')->bearerFromCredential('a|b')),
    'Credentials must stay in headers and name plain push data fields.',
);
NotificationCredentials::set(' User-A ', 'token-a');
$assert($lastCall()['method'] === 'setCredential' && $lastCall()['values'] == ['account' => 'user-a', 'token' => 'token-a'], 'set must normalize the account id.');
NotificationCredentials::remove('USER-A');
$assert($lastCall()['method'] === 'removeCredential' && $lastCall()['values'] == ['account' => 'user-a'], 'remove must normalize the account id.');
NotificationCredentials::sync(['User-B' => 'token-b']);
$assert(
    $lastCall()['method'] === 'replaceCredentials' && $lastCall()['values'] == ['tokens' => '{"user-b":"token-b"}'],
    'sync must replace the native credential map.',
);
NotificationCredentials::clear();
$assert($lastCall()['method'] === 'replaceCredentials' && $lastCall()['values'] == ['tokens' => '{}'], 'clear must empty the native credential map.');
$assert(
    $rejects(static fn () => NotificationCredentials::set('', 't'))
        && $rejects(static fn () => NotificationCredentials::set('a', "t\nx"))
        && $rejects(static fn () => NotificationCredentials::set('a', '')),
    'Credentials must validate accounts and tokens.',
);

PushRendering::forType('chat.message')
    ->conversation('{chat_id}', title: '{chat_title}', group: '{is_group}')
    ->message(sender: '{sender_name}', text: '{body}', avatar: '{sender_avatar}', timestamp: '{sent_at}', id: '{message_id}')
    ->self('Você')
    ->reply('Responder')
    ->markRead('Marcar como lida')
    ->deepLink('example://chat/{chat_id}')
    ->channel('messages', 'Mensagens')
    ->suppressWhenRoute('chat/{chat_id}')
    ->suppressWhenRoute('chat', ['chatId' => '{chat_id}'])
    ->register();
$rule = json_decode((string) $lastCall()['values']['rule'], true);
$assert(
    $lastCall()['method'] === 'registerPushRendering'
        && $rule['type'] === 'chat.message' && $rule['field'] === 'type' && $rule['kind'] === 1
        && $rule['conversation'] === ['key' => '{chat_id}', 'title' => '{chat_title}', 'group' => '{is_group}']
        && $rule['message']['sender'] === '{sender_name}' && $rule['self'] === 'Você'
        && $rule['reply'] === ['label' => 'Responder', 'endpoint' => null]
        && $rule['suppress'] === [['path' => 'chat/{chat_id}'], ['route' => 'chat', 'params' => ['chatId' => '{chat_id}']]]
        && $rule['channelId'] === 'messages',
    'PushRendering must persist a declarative conversation rule.',
);
PushRendering::forType('chat.read')->dismissConversation('{chat_id}')->register();
$assert(json_decode((string) $lastCall()['values']['rule'], true)['kind'] === 3, 'PushRendering must support dismiss rules.');
$assert(
    $rejects(static fn () => PushRendering::forType('x')->register())
        && $rejects(static fn () => PushRendering::forType('x')->conversation('{a}')->notification()),
    'PushRendering rules need exactly one presentation.',
);

$chatNavigator = new Navigator(
    initialRoute: 'inbox',
    routes: [
        'inbox' => static fn () => Screen::make(Text::make('Inbox')),
        'chat' => static fn () => Screen::make(Text::make('Chat')),
    ],
    persistenceKey: 'push-route-test',
    deepLinks: [new DeepLink('/chat/{chatId}', 'chat')],
);
$chatNavigator->navigate('chat', ['chatId' => '42']);
$chatNavigator->render();
$route = $lastCall();
$assert(
    $route['method'] === 'setActiveRoute' && $route['values']['name'] === 'chat'
        && json_decode((string) $route['values']['params'], true) === ['chatId' => '42']
        && $route['values']['path'] === '/chat/42',
    'Focused routes must be reported for push suppression once rules need them.',
);
// A screen known by a second id reports it as an alias of its focused route.
$aliasHandle = PushRendering::routeAliases('chat', ['chatId' => '42'], ['loadedChatId' => 'uuid-42', 'chatId' => 'ignored']);
$route = $lastCall();
$assert(
    $route['method'] === 'setActiveRoute' && $route['values']['name'] === 'chat'
        && json_decode((string) $route['values']['params'], true) === ['chatId' => '42', 'loadedChatId' => 'uuid-42'],
    'routeAliases must re-report the focused route with the alias, route params winning.',
);
$otherHandle = PushRendering::routeAliases('chat', ['chatId' => '7'], ['loadedChatId' => 'uuid-7']);
$assert(
    json_decode((string) $lastCall()['values']['params'], true) === ['chatId' => '42', 'loadedChatId' => 'uuid-42'],
    'Aliases of another chat must not leak into the focused route.',
);
PushRendering::forgetRouteAliases($aliasHandle);
$assert(
    $lastCall()['method'] === 'setActiveRoute' && json_decode((string) $lastCall()['values']['params'], true) === ['chatId' => '42'],
    'forgetRouteAliases must re-report the focused route without the alias.',
);
PushRendering::forgetRouteAliases($otherHandle);
PushRendering::forgetRouteAliases(999);
$assert($rejects(static fn () => PushRendering::routeAliases('', [], ['a' => 'b'])), 'Aliased routes need a name.');

PushRendering::forget('chat.read');
$assert($lastCall()['method'] === 'forgetPushRendering' && $lastCall()['values'] == ['type' => 'chat.read', 'field' => 'type'], 'PushRendering::forget must remove one rule.');

// 9c. Conditional reply, reply failures, buttons, extra matches and iOS categories.
PushRendering::forType('1', 'chat_type')
    ->conversation('chat_{chat_id}', title: '{chat_title}')
    ->message(sender: '{actor_name}', text: '{message_preview}', id: '{message_id}')
    ->reply(
        'Responder',
        ActionEndpoint::post('https://api.example.test/chats/{chat_id}/notification-replies')
            ->bearerFromCredential('user_id')
            ->json(['body' => '{reply}', 'client_message_id' => '{action_id}', 'idempotency_key' => 'notification.reply:{chat_id}:{action_id}']),
        hideWhen: ['can_reply' => '0'],
    )
    ->replyFailures(
        ReplyFailures::make(sender: 'Não enviada', retryLabel: 'Tentar de novo', draftField: 'reply_draft')
            ->offline('Sem conexão. Toque em Tentar de novo.')
            ->status(401, 'Sua sessão expirou. Abra o Zé para enviar.')
            ->status(403, 'Você não pode responder nesta conversa.', serverMessage: true)
            ->status([409, 422, 429], 'Não foi possível enviar a resposta.', retry: true, keepReply: true, serverMessage: true)
            ->otherwise('O Zé não respondeu. Toque em Tentar de novo.'),
    )
    ->category('ZE_MESSAGE_REPLY', withoutReply: 'ZE_MESSAGE')
    ->register();
$rule = json_decode((string) $lastCall()['values']['rule'], true);
$assert(
    $rule['reply']['hideWhen'] === ['can_reply' => '0']
        && $rule['replyFailures']['sender'] === 'Não enviada' && $rule['replyFailures']['retryLabel'] === 'Tentar de novo'
        && $rule['replyFailures']['draftField'] === 'reply_draft'
        && $rule['replyFailures']['offline'] === ['text' => 'Sem conexão. Toque em Tentar de novo.', 'retry' => true, 'keepReply' => true, 'serverMessage' => false]
        && $rule['replyFailures']['statuses'][0] === ['codes' => [401], 'text' => 'Sua sessão expirou. Abra o Zé para enviar.', 'retry' => false, 'keepReply' => false, 'serverMessage' => false]
        && $rule['replyFailures']['statuses'][2]['codes'] === [409, 422, 429] && $rule['replyFailures']['statuses'][2]['retry'] === true
        && $rule['replyFailures']['otherwise'] === ['text' => 'O Zé não respondeu. Toque em Tentar de novo.', 'retry' => true, 'keepReply' => true, 'serverMessage' => false]
        && $rule['category'] === ['identifier' => 'ZE_MESSAGE_REPLY', 'withoutReply' => 'ZE_MESSAGE'],
    'Replies must support a per-push hide condition, failure presentation and iOS categories.',
);
PushRendering::forType('5')
    ->where('system_event', 'post_collaboration_invite')
    ->requires('collaboration_invite_id')
    ->notification('{title}', '{body}')
    ->action('decline', 'Recusar', ActionEndpoint::post('https://api.example.test/post-collaborations/{collaboration_invite_id}/respond')->json(['status' => 3]))
    ->action('accept', 'Aceitar', ActionEndpoint::post('https://api.example.test/post-collaborations/{collaboration_invite_id}/respond')->json(['status' => 2]), dismiss: false)
    ->category('POST_COLLABORATION_INVITE')
    ->register();
$rule = json_decode((string) $lastCall()['values']['rule'], true);
$assert(
    $rule['where'] === ['system_event' => 'post_collaboration_invite'] && $rule['requires'] === ['collaboration_invite_id']
        && array_column($rule['actions'], 'id') === ['decline', 'accept'] && $rule['actions'][0]['label'] === 'Recusar'
        && $rule['actions'][0]['dismiss'] === true && $rule['actions'][1]['dismiss'] === false
        && $rule['actions'][1]['endpoint']['body'] === ['status' => 2],
    'Rules must match extra data fields and carry background action buttons.',
);
$assert(
    $rejects(static fn () => PushRendering::forType('5')->notification()->action('bad id', 'X', ActionEndpoint::post('https://x.test')))
        && $rejects(static fn () => PushRendering::forType('5')->notification()->where('bad field', 'x'))
        && $rejects(static fn () => PushRendering::forType('5')->notification()->category('bad category!'))
        && $rejects(static fn () => ReplyFailures::make()->status(99, 'x'))
        && $rejects(static fn () => PushRendering::forType('5')->notification()->reply('R', null, hideWhen: ['bad field' => '0'])),
    'Buttons, matches, categories and failures must be validated.',
);
Runtime::dispatchModuleResult($pendingActionCall['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'type' => 3, 'conversation' => '', 'text' => '', 'data' => '{"collaboration_invite_id":"9"}', 'deepLink' => '',
    'handledNatively' => true, 'statusCode' => 200, 'timestamp' => 7, 'action' => 'accept',
]));
$last = $actions[count($actions) - 1];
$assert($last->type === NotificationActionType::Button && $last->action === 'accept' && $last->delivered() && !$last->failureShown, 'Button taps must reach PHP with their id.');

