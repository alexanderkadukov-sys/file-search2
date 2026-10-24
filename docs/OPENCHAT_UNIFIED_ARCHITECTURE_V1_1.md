# OpenChat Unified — Engineering Blueprint v1.1
**Дата:** 2026-10-25
**Статус:** техническое решение / поэтапная реализация; внешние аккаунты ещё не подключены.
**Главная цель:** личный агрегатор сообщений в полноценном Android-клиенте, без потери текущего публичного OpenChat.

## 0. Коррекция исходного плана
Присланный план FastAPI / PostgreSQL / Redis / WebSockets корректен как типовая архитектура *бизнес-омниканала*, но не как готовый доступ к личным аккаунтам WhatsApp, VK или MAX. Webhook Telegram Bot API даёт доступ к сообщениям, адресованным боту; не ко всем личным перепискам владельца. Реальный пользовательский Telegram клиент строится на TDLib/Telegram API. Для остальных платформ возможности нуждаются в проверке разрешений и официальной документации; нельзя обещать фоновое чтение чужих данных.

### Состояние провайдеров и безопасный выпуск
| Источник | Личные сообщения | Первый допустимый режим |
|---|---|---|
| Telegram | Официальный клиентский API (TDLib/MTProto) | Только с согласия владельца, отдельная пользовательская сессия; чтение -> подтверждённая отправка |
| WhatsApp Messenger | Общедоступного personal-sync API нет | Ссылки/Share sheet/локальный экспорт, без фонового чтения; Business Platform — другой продукт |
| VK личный | Доступность конкретного permission зависит от приложения и политики VK | Proof-of-access на тестовом аккаунте; при отсутствии разрешения — ручной импорт / переход |
| MAX личный | Документация API описывает ботов | Ручной импорт/переход; бот — отдельное подключение, не личный аккаунт |

## 1. Архитектурные границы
- **Public Chat** (существующее `chat/` + Supabase) остаётся публичным без регистрации. Ни одно изменение схемы/публикации личного Inbox не должно менять поведение этого контура.
- **Private Inbox** — самостоятельный интерфейс и авторизованный сервер. Нельзя переиспользовать анонимную публичную сессию как удостоверение владельца.
- **Android client** — в целевом варианте Jetpack Compose + Material 3, адаптивное состояние списка/диалога, корректный WindowInsets, системные back/keyboard, сохранение черновика, локальные уведомления. Временный WebView используется только для текущих публичных комнат.
- **Backend** — FastAPI service + provider adapters + durable outbox worker. Telegram TDLib можно разместить на контролируемом устройстве/сервере с защищённым диском; сеансы и коды входа никогда не передавать на GitHub Pages.
- **Persistence** — отдельный проект Supabase (предпочтительно, меньше рисков пересечения) с Postgres, закрытым Storage, Authentication, Realtime по правилам доступа. Redis/очередь отдельного брокера только после метрик; первое решение — транзакционная outbox-таблица в Postgres.
- Без бессмысленного дублирования: при MVP Supabase Realtime уже даёт события, второй произвольный WebSocket-сервер не требуется.

## 2. Модель данных v1
`identities` / `owner_accounts`: локальный собственник, его разрешённые интеграции, статусы подключений, revoked_at; не хранить секреты plaintext.
`contacts`: логическая сущность человека; идентификатор UUID, display_name, notes. Слияние разных каналов строго подтверждается пользователем.
`contact_handles`: provider, provider_account_id, external_user_id, contact_id, verified_at, last_seen_at, unique(provider,provider_account_id,external_user_id). Никнейм или совпадающий номер без подтверждения не означает, что это тот же человек.
`conversations`: provider_account_id, external_chat_id, kind, contact_id?; уникальность (provider_account_id, external_chat_id). Группы могут быть без одного contact_id.
`messages`: id, conversation_id, provider_message_id, direction, sender_handle_id, body, content_type, provider_created_at, received_at, edited_at, deleted_at, privacy_flags, delivery_state. unique(conversation_id, provider_message_id) для полученных сообщений, где API даёт ID.
`attachments`: message_id, private_storage_key, content_type, size, checksum, source_id, upload_state. Не хранить вечные публичные media_url.
`sync_cursors`: provider_account_id, conversation_id?, opaque_cursor, cursor_version, synchronized_at; транзакционные checkpoint.
`outbox_operations`: id, provider_account_id, conversation_id, payload_digest, client_request_id (unique), state, attempt_count, lease_token, lease_expires_at, provider_result_id, next_retry_at, last_error.
`audit_events`: owner, action, target, source, created_at, redacted_metadata, immutable.

### Критические ограничения
1. Нельзя выбирать канал ответа по `MAX(created_at)` автоматически. Отправка должна включать подтверждённый `provider_account_id + conversation_id`, проверку capabilities и контекста политики платформы.
2. Идемпотентность — provider message key для inbound; client_request_id и durable outbox для outbound. Retry только если точно безопасен; при неизвестном результате отправки — reconcile, а не повтор вслепую.
3. Source-of-truth — исходная платформа. Удалённые/исчезающие сообщения требуют политики копирования, согласия и правовых ограничений; никакого скрытого бессрочного архивирования.
4. RLS + ownership на каждой доступной таблице; `anon` не получает никаких прав на Inbox. Серверный service role не помещать в Android или GitHub Pages. MFA для админ-сессий, encrypted-at-rest backup, проверка восстановления, защита от replay/webhook forgery.
5. Provider credentials / TDLib session secrets хранятся только на доверенном сервере или локально в Android Keystore; никакие секреты не помещаются в SQLite без дополнительного шифрования либо в браузерный localStorage.

## 3. Контракт провайдера
```typescript
type Provider = 'telegram' | 'whatsapp' | 'vk' | 'max';
type Capability = 'read' | 'send' | 'attachments' | 'edits' | 'reactions' | 'history';
interface ConnectionHealth {
  provider: Provider; accountId: string;
  mode: 'user_client' | 'business_api' | 'bot' | 'manual';
  status: 'disconnected' | 'needs_action' | 'syncing' | 'ready' | 'degraded';
  capabilities: ReadonlyArray<Capability>;
}
interface IncomingEvent {
  providerAccountId: string; externalChatId: string;
  externalMessageId: string; eventType: 'created' | 'edited' | 'deleted';
  occurredAt: string; payload: unknown;
}
interface SendCommand {
  ownerId: string; providerAccountId: string; externalChatId: string;
  text: string; clientRequestId: string; // never infer channel from last message
}
```
Provider connector must implement auth/revoke/health, full-or-partial initial import, incremental polling/webhook or TDLib update subscription, media download and send with explicit capability flags. UI displays `not available` rather than fake successful sync.

## 4. Data Flow
**Inbound:** official provider event -> verify origin (when supported) -> normalize -> de-duplicate -> commit messages + cursors -> authorized Realtime notification -> UI -> optional media download.

**Outbound:** explicit destination and permission check -> commit outbox intent -> worker obtains lease + fencing -> provider send -> persist provider result, transition state -> UI state (queued/sending/sent/failed). Never mark delivered/read unless provider provides trustworthy acknowledgment.

**Recovery:** reconnect, cursor replay, idempotent upsert, orphan detection, exponential backoff with provider rate limits, audit. Stable source IDs and immutable audit trail.

## 5. Реализация этапами / критерии
### Phase A — UI & platform stabilization
- Status bar and gesture/IME insets Android 15/16 tested on actual phone.
- Professional Material 3 screens: inbox list, searchable conversations, channel badges, conversation, attachments, account settings, connection diagnostics, dark/light.
- Current public chat remains in a **separate, clearly labeled** section, with correct warning that all messages are public.
- New clients may preview empty Inbox shell but may not show fabricated connected provider status or personal messages.

### Phase B — Private security foundation
- Separate Supabase project / private database, Auth+MFA, RLS coverage and fail-closed tests.
- Schema + indexes + migration + encrypted private Storage + point-in-time backup strategy.
- Outbox, dedup, cursor recovery integration tests and representative test fixtures; no production personal data yet.

### Phase C — Personal Telegram
- Register Telegram API application; user authorizes login, optional 2FA; TDLib operates in trusted native/service process.
- Read-only import chosen chats -> compare history -> live sync -> messages with attachments -> send/ack after explicit enablement.
- Test: second real Telegram account sends message; appears in Inbox; response delivered, reconnect resumes without duplicates.

### Phase D — Other platform gates
- VK: verify actual scopes and API return before any UI `connected` state.
- WhatsApp personal: local Share sheet / import archive / deep link only. Never request user to delete their Messenger account for Cloud API.
- MAX personal: import/deep link only until an officially supported user client API is documented; bot credentials never masquerade as personal session.
- If viable separate **business** accounts requested later, implement explicit integration modes with their own permissions.

## 6. Release gates
- NO production DB schema mutation until reviewed separate auth boundary and backup/restore plan.
- NO release of connector with `send` before read-only QA, idempotency and capability checks.
- NO app download claim without successful CI compile, archive verification and real device smoke test.
- NO assertions of private/E2E chat; duplicated data has its own security and retention risk.

## Документация первоисточников
- https://core.telegram.org/tdlib
- https://core.telegram.org/
- https://www.postman.com/meta/whatsapp-business-platform/collection/wlk6lh4/whatsapp-cloud-api
- https://dev.max.ru/docs-api
- https://developer.android.com/develop/ui/views/layout/edge-to-edge
- https://supabase.com/docs/guides/database/postgres/row-level-security
