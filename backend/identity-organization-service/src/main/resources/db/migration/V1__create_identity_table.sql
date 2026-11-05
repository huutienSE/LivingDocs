-- LIV-114 - S01 Identity & Organization
-- Initial Identity persistence

CREATE TABLE identity.identities (
    identity_id   UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    email         VARCHAR(320) NOT NULL,
    password_hash TEXT,

    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_identities_email_not_blank CHECK (BTRIM(email) <> '')
);

-- Email must be unique regardless of letter case
CREATE UNIQUE INDEX uq_identities_email ON identity.identities(LOWER(BTRIM(email)));


GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE identity.identities TO ld_s01_app;