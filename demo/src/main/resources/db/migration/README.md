# Migrations

The schema, as numbered SQL files. The app applies any that have not run yet on
startup, each in its own transaction, and records them in `schema_migration`.

## Adding one

1. Create the next file: `V0NN__what_it_does.sql`.
2. Append its filename to `MIGRATIONS` in `stockapp/Db.java`. The list is
   explicit because a shaded jar cannot list a classpath directory.
3. Start the app; the log says `Applied migration V0NN__…`.

## Rules

- **Never edit a migration that has shipped.** It has already run on every
  existing database; write a new one instead.
- Write migrations so they can be re-read safely — `IF NOT EXISTS`, explicit
  constraint names.
- One migration, one purpose. Name it for what it does.
