-- OpenChat / Supabase PostgreSQL setup, v1
-- Выполните целиком в Supabase → SQL Editor → New query → Run.
-- Не требует таблиц/прав существующих проектов: создавайте отдельный проект Supabase.
create extension if not exists pgcrypto;

create table if not exists public.chat_messages (
  id uuid primary key default gen_random_uuid(),
  room text not null check (room in ('general','photos','files')),
  sender_id uuid not null,
  nickname text not null check (char_length(nickname) between 2 and 24),
  body text not null default '' check (char_length(body) <= 3000),
  file_path text,
  file_name text,
  file_type text,
  file_size integer,
  created_at timestamptz not null default now(),
  constraint chat_message_not_empty check (char_length(btrim(body)) > 0 or file_path is not null),
  constraint chat_file_metadata check (
    (file_path is null and file_name is null and file_type is null and file_size is null)
    or (file_path is not null and file_name is not null and file_type is not null and file_size is not null)
  ),
  constraint chat_file_types check (file_type is null or file_type in
    ('image/jpeg','image/png','image/webp','image/gif','application/pdf','text/plain')),
  constraint chat_file_size check (file_size is null or file_size between 0 and 8388608),
  constraint chat_file_name_length check (file_name is null or char_length(file_name) <= 180)
);
create index if not exists chat_messages_room_created_idx
  on public.chat_messages (room, created_at desc);
create index if not exists chat_messages_sender_created_idx
  on public.chat_messages (sender_id, created_at desc);
alter table public.chat_messages enable row level security;
revoke all on public.chat_messages from anon;
grant select, insert on public.chat_messages to authenticated;

drop policy if exists "chat_read_authenticated" on public.chat_messages;
create policy "chat_read_authenticated"
  on public.chat_messages for select to authenticated using (true);

drop policy if exists "chat_post_as_self" on public.chat_messages;
create policy "chat_post_as_self"
  on public.chat_messages for insert to authenticated with check (
    sender_id = (select auth.uid())
    and (file_path is null or split_part(file_path, '/', 1) = (select auth.uid())::text)
  );

-- Защита от подмены времени отправки и слишком частых сообщений.
-- Важное ограничение: лимит по anonymous user id, НЕ по IP.
create or replace function public.openchat_before_message_insert()
returns trigger language plpgsql security definer
set search_path = pg_catalog, public
as $$
begin
  if auth.uid() is null or new.sender_id <> auth.uid() then
    raise exception 'sender identity mismatch';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(new.sender_id::text, 0));
  if (select count(*) from public.chat_messages
      where sender_id = new.sender_id
        and created_at > clock_timestamp() - interval '1 minute') >= 12 then
    raise exception 'rate limit: 12 messages per minute';
  end if;
  new.created_at := clock_timestamp();
  return new;
end;
$$;
drop trigger if exists openchat_messages_limit on public.chat_messages;
create trigger openchat_messages_limit before insert on public.chat_messages
  for each row execute function public.openchat_before_message_insert();

-- Публичный bucket нужен потому, что чат публичный.
-- Допускаются только безопасные для MVP типы файлов (не HTML/SVG/EXE).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'chat-uploads', 'chat-uploads', true, 8388608,
  array['image/jpeg','image/png','image/webp','image/gif','application/pdf','text/plain']::text[]
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "chat_owner_upload" on storage.objects;
create policy "chat_owner_upload" on storage.objects
  for insert to authenticated with check (
    bucket_id = 'chat-uploads' and
    (storage.foldername(name))[1] = (select auth.uid())::text
  );
drop policy if exists "chat_owner_view_metadata" on storage.objects;
create policy "chat_owner_view_metadata" on storage.objects
  for select to authenticated using (
    bucket_id = 'chat-uploads' and
    (storage.foldername(name))[1] = (select auth.uid())::text
  );
drop policy if exists "chat_owner_cleanup" on storage.objects;
create policy "chat_owner_cleanup" on storage.objects
  for delete to authenticated using (
    bucket_id = 'chat-uploads' and
    (storage.foldername(name))[1] = (select auth.uid())::text
  );

-- Включить поток INSERT для браузерных подписчиков.
do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'chat_messages'
  ) then
    alter publication supabase_realtime add table public.chat_messages;
  end if;
end;
$$;

-- !!! Продакшен: включите Turnstile/CAPTCHA на anonymous sign-in,
-- настройте мониторинг расходов Storage, модерацию и реакцию на abuse.
-- Вывод персональных данных и доверенный приватный обмен здесь запрещён.
