-- V012 - Bank accounts linked through Enable Banking.
--
-- A link is one BankID consent at one bank: the session Enable Banking returned
-- and how long the consent lasts. Its accounts are synced into an ordinary
-- account and snapshot, the same way eToro is, until the consent expires.

CREATE TABLE IF NOT EXISTS bank_link (
    id          SERIAL      PRIMARY KEY,
    bank_name   TEXT        NOT NULL,
    country     TEXT        NOT NULL,
    session_id  TEXT        NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT bank_link_session_unique UNIQUE (session_id)
);

CREATE TABLE IF NOT EXISTS bank_link_account (
    link_id     INTEGER NOT NULL REFERENCES bank_link (id) ON DELETE CASCADE,
    account_uid TEXT    NOT NULL,
    name        TEXT,
    identifier  TEXT,
    currency    TEXT,
    PRIMARY KEY (link_id, account_uid)
);
