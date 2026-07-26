CREATE TYPE "user_role" AS ENUM ('USER', 'administrator');

CREATE TABLE "Post" (
  "id" bigserial NOT NULL,
  "title" VarChar(200) NOT NULL,
  "body" text,
  "views" integer NOT NULL DEFAULT 0,
  "draft" boolean NOT NULL DEFAULT TRUE,
  "authorId" integer NOT NULL,
  CONSTRAINT "Post_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "Profile" (
  "id" serial NOT NULL,
  "bio" text,
  "userId" integer NOT NULL,
  CONSTRAINT "Profile_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "Tag" (
  "id" serial NOT NULL,
  "name" text NOT NULL,
  CONSTRAINT "Tag_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "_PostTags" (
  "A" bigint NOT NULL,
  "B" integer NOT NULL
);

CREATE TABLE "post_comments" (
  "postId" bigint NOT NULL,
  "authorId" integer NOT NULL,
  "body" text NOT NULL,
  "createdAt" timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "post_comments_pkey" PRIMARY KEY ("postId", "authorId")
);

CREATE TABLE "users" (
  "id" serial NOT NULL,
  "email" VarChar(320) NOT NULL,
  "name" text,
  "role" "user_role" NOT NULL DEFAULT 'USER',
  "created_at" timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updated_at" timestamp(3) NOT NULL,
  "managerId" integer,
  CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "Post_authorId_idx" ON "Post" ("authorId");

CREATE INDEX "Post_title_body_idx" ON "Post" USING GIN (to_tsvector('simple', coalesce("title", '') || ' ' || coalesce("body", '')));

ALTER TABLE "Profile" ADD CONSTRAINT "Profile_userId_key" UNIQUE ("userId");

ALTER TABLE "Tag" ADD CONSTRAINT "Tag_name_key" UNIQUE ("name");

ALTER TABLE "_PostTags" ADD CONSTRAINT "_PostTags_A_B_key" UNIQUE ("A", "B");

CREATE INDEX "_PostTags_B_idx" ON "_PostTags" ("B");

ALTER TABLE "users" ADD CONSTRAINT "users_email_key" UNIQUE ("email");

CREATE INDEX "users_email_created_at_idx" ON "users" ("email", "created_at");

ALTER TABLE "Post" ADD CONSTRAINT "Post_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "users" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "Profile" ADD CONSTRAINT "Profile_userId_fkey" FOREIGN KEY ("userId") REFERENCES "users" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "_PostTags" ADD CONSTRAINT "_PostTags_A_fkey" FOREIGN KEY ("A") REFERENCES "Post" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "_PostTags" ADD CONSTRAINT "_PostTags_B_fkey" FOREIGN KEY ("B") REFERENCES "Tag" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "post_comments" ADD CONSTRAINT "post_comments_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "users" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "post_comments" ADD CONSTRAINT "post_comments_postId_fkey" FOREIGN KEY ("postId") REFERENCES "Post" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "users" ADD CONSTRAINT "users_managerId_fkey" FOREIGN KEY ("managerId") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE;
