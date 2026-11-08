-- Nimisham shared database. Paste into Supabase → SQL Editor → Run. Safe to run again.
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
