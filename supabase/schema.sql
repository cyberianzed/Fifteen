-- Fifteen shared database. Paste into Supabase → SQL Editor → Run. Safe to run again.
-- One table holds every record (blocks, categories, profile) as JSON, scoped to the signed-in user.

create table if not exists public.records (
  user_id    uuid   not null default auth.uid() references auth.users(id) on delete cascade,
  kind       text   not null,
  id         text   not null,
  data       jsonb  not null,
  updated_at bigint not null,
  seq        bigint not null default 0,
  primary key (user_id, kind, id)
);

create sequence if not exists public.records_seq;

-- Every write gets a new, increasing seq so devices can pull "everything after N".
-- An older edit that arrives late (offline device) never overwrites a newer one.
create or replace function public.records_stamp() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' and new.updated_at < old.updated_at then
    return old;
  end if;
  new.seq := nextval('public.records_seq');
  return new;
end $$;

drop trigger if exists records_stamp on public.records;
create trigger records_stamp before insert or update on public.records
  for each row execute function public.records_stamp();

create index if not exists records_user_seq on public.records (user_id, seq);

alter table public.records enable row level security;
drop policy if exists "own rows" on public.records;
create policy "own rows" on public.records for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

grant select, insert, update on public.records to authenticated;

-- ---------------------------------------------------------------------------------------------------------
-- Sync keys (Fifteen 1.2+): sync with a shared key instead of an account. Rows are filed under a sync id
-- derived from the key (sent in the x-sync-id header; the key itself never reaches the server), and their
-- data is encrypted on the device, so this table only ever holds scrambled JSON. Safe to run again; it
-- doesn't touch the email-account table above.
-- ---------------------------------------------------------------------------------------------------------

create table if not exists public.key_records (
  sync_id    text   not null check (char_length(sync_id) = 64),
  kind       text   not null,
  id         text   not null,
  data       jsonb  not null check (octet_length(data::text) < 20000),
  updated_at bigint not null,
  seq        bigint not null default 0,
  primary key (sync_id, kind, id)
);

-- Same stamping as records: a newer seq on every write; a late, older edit never overwrites a newer one.
-- A cap per key keeps one key from filling the database.
create or replace function public.key_records_stamp() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' and new.updated_at < old.updated_at then
    return old;
  end if;
  if tg_op = 'INSERT' and (select count(*) from public.key_records where sync_id = new.sync_id) >= 100000 then
    raise exception 'This sync key has too many records';
  end if;
  new.seq := nextval('public.records_seq');
  return new;
end $$;

drop trigger if exists key_records_stamp on public.key_records;
create trigger key_records_stamp before insert or update on public.key_records
  for each row execute function public.key_records_stamp();

create index if not exists key_records_sync_seq on public.key_records (sync_id, seq);

-- Only requests that carry the sync id can see or write its rows.
alter table public.key_records enable row level security;
drop policy if exists "rows of this key" on public.key_records;
create policy "rows of this key" on public.key_records for all to anon, authenticated
  using (sync_id = coalesce(current_setting('request.headers', true)::json ->> 'x-sync-id', ''))
  with check (sync_id = coalesce(current_setting('request.headers', true)::json ->> 'x-sync-id', ''));

grant select, insert, update on public.key_records to anon, authenticated;
