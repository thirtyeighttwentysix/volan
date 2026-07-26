-- The tables blog.volan describes, written by hand.
--
-- Volan generates these from the schema from M6 onwards; until the migration engine exists, the
-- integration tests state them so that what the client writes is checked against a real database
-- rather than against an assumption.

create table users (
    id          serial primary key,
    email       varchar(320) not null unique,
    name        text,
    role        text        not null default 'USER',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null,
    "managerId" integer references users (id) on delete set null
);

create table "Profile" (
    id       serial primary key,
    bio      text,
    "userId" integer not null unique references users (id) on delete cascade
);

create table "Post" (
    id         bigserial primary key,
    title      varchar(200) not null,
    body       text,
    views      integer not null default 0,
    draft      boolean not null default true,
    "authorId" integer not null references users (id) on delete cascade
);

create table "Tag" (
    id   serial primary key,
    name text not null unique
);

-- The join table of the implicit many-to-many between Post and Tag. Volan names it after the relation
-- and calls its columns A and B, in the order the relation's ends are held.
create table "_PostTags" (
    "A" bigint  not null references "Post" (id) on delete cascade,
    "B" integer not null references "Tag" (id) on delete cascade,
    primary key ("A", "B")
);

create table post_comments (
    "postId"    bigint  not null references "Post" (id) on delete cascade,
    "authorId"  integer not null references users (id) on delete cascade,
    body        text    not null,
    "createdAt" timestamptz not null default now(),
    primary key ("postId", "authorId")
);
