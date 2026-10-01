create table users (
    id integer primary key autoincrement,
    email text not null unique,
    name text,
    role text not null default 'USER',
    created_at text not null default (strftime('%Y-%m-%dT%H:%M:%f000000Z', 'now')),
    updated_at text not null
);
create table "Post" (
    id integer primary key autoincrement,
    title text not null,
    views integer not null default 0,
    draft integer not null default 1,
    "authorId" integer not null references users(id) on delete cascade
);
create table "Tag" (
    id integer primary key autoincrement,
    name text not null unique
);
create table "_PostTags" (
    "A" integer not null references "Post"(id) on delete cascade,
    "B" integer not null references "Tag"(id) on delete cascade,
    primary key ("A", "B")
);
create table "Comment" (
    "postId" integer not null references "Post"(id) on delete cascade,
    "authorId" integer not null references users(id) on delete cascade,
    body text not null,
    primary key ("postId", "authorId")
);
create table "Scalars" (
    id integer primary key autoincrement,
    ratio real not null,
    precise real not null,
    enabled integer not null,
    "when" text,
    date text,
    time text,
    token text,
    data blob,
    document text
);
