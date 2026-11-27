-- LIV-151: transient onboarding only. No Repository Connection or S01 Grant is created.
CREATE TABLE repository.github_authorization_contexts (
    context_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_identity_id UUID NOT NULL,
    app_id VARCHAR(19) NOT NULL CHECK (app_id ~ '^[1-9][0-9]{0,18}$'),
    app_config_version VARCHAR(64) NOT NULL,
    state_digest VARCHAR(64) NOT NULL CHECK (state_digest ~ '^[0-9a-f]{64}$'),
    browser_nonce_digest VARCHAR(64) NOT NULL CHECK (browser_nonce_digest ~ '^[0-9a-f]{64}$'),
    state_key_version VARCHAR(32) NOT NULL,
    credential_key_version VARCHAR(32) NOT NULL,
    secret_payload VARCHAR(16384),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','EXCHANGING','AUTHORIZED','FAILED','CANCELLED','EXPIRED')),
    github_user_id VARCHAR(19),
    expires_at TIMESTAMPTZ NOT NULL,
    token_expires_at TIMESTAMPTZ,
    row_revision BIGINT NOT NULL DEFAULT 0 CHECK (row_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_gh_authorization_context_ttl CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '15 minutes'),
    CONSTRAINT ck_gh_authorization_context_payload CHECK (
        (status IN ('PENDING','EXCHANGING') AND secret_payload IS NOT NULL AND github_user_id IS NULL AND token_expires_at IS NULL)
        OR (status = 'AUTHORIZED' AND secret_payload IS NOT NULL AND github_user_id IS NOT NULL AND github_user_id ~ '^[1-9][0-9]{0,18}$'
            AND token_expires_at IS NOT NULL AND expires_at <= token_expires_at)
        OR (status IN ('FAILED','CANCELLED','EXPIRED') AND secret_payload IS NULL)
    )
);

CREATE INDEX ix_gh_authorization_context_owner ON repository.github_authorization_contexts
    (tenant_id, actor_identity_id, workspace_id, expires_at) WHERE status IN ('PENDING','EXCHANGING','AUTHORIZED');
CREATE INDEX ix_gh_authorization_context_expiry ON repository.github_authorization_contexts (expires_at)
    WHERE secret_payload IS NOT NULL;
ALTER TABLE repository.github_authorization_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE repository.github_authorization_contexts FORCE ROW LEVEL SECURITY;
CREATE POLICY gh_authorization_context_tenant ON repository.github_authorization_contexts TO ld_s02_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);
GRANT SELECT, INSERT ON repository.github_authorization_contexts TO ld_s02_app;
GRANT UPDATE (status, secret_payload, github_user_id, token_expires_at, expires_at, row_revision, updated_at)
    ON repository.github_authorization_contexts TO ld_s02_app;

-- FORCE RLS remains enabled for the function owner. Its policies expose only expired rows.
CREATE POLICY gh_authorization_expired_maintenance_select ON repository.github_authorization_contexts
    FOR SELECT TO ld_s02_migrator USING (expires_at <= statement_timestamp());
CREATE POLICY gh_authorization_expired_maintenance_update ON repository.github_authorization_contexts
    FOR UPDATE TO ld_s02_migrator USING (expires_at <= statement_timestamp())
    WITH CHECK (expires_at <= statement_timestamp());

CREATE FUNCTION repository.purge_expired_github_authorization_contexts() RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE affected INTEGER;
BEGIN
    WITH expired AS (
        SELECT context_id FROM repository.github_authorization_contexts
        WHERE expires_at <= statement_timestamp()
          AND (secret_payload IS NOT NULL OR status IN ('PENDING','EXCHANGING','AUTHORIZED'))
        ORDER BY expires_at, context_id LIMIT 100 FOR UPDATE SKIP LOCKED
    )
    UPDATE repository.github_authorization_contexts AS context
    SET status='EXPIRED', secret_payload=NULL, row_revision=row_revision+1, updated_at=statement_timestamp()
    WHERE context.context_id IN (SELECT context_id FROM expired);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected;
END;
$$;
ALTER FUNCTION repository.purge_expired_github_authorization_contexts() OWNER TO ld_s02_migrator;
REVOKE ALL ON FUNCTION repository.purge_expired_github_authorization_contexts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repository.purge_expired_github_authorization_contexts() TO ld_s02_app;
