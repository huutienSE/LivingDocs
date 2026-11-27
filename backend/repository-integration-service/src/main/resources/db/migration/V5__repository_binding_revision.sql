-- S01 grants bind to a scalar revision. Assign only while holding the repository row lock:
-- max(github revision, repository revision) is unsafe when their transactions commit out of order.
ALTER TABLE repository.repository_connections
    ADD COLUMN binding_revision bigint NOT NULL DEFAULT nextval('repository.source_configuration_revision_seq');
ALTER TABLE repository.repository_connections ALTER COLUMN binding_revision DROP DEFAULT;
ALTER TABLE repository.repository_connections ADD COLUMN binding_parent_revision bigint;
UPDATE repository.repository_connections r SET binding_parent_revision=g.config_revision
FROM repository.github_connections g WHERE g.tenant_id=r.tenant_id AND g.github_connection_id=r.github_connection_id;
ALTER TABLE repository.repository_connections ALTER COLUMN binding_parent_revision SET NOT NULL;

CREATE FUNCTION repository.assign_repository_binding_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.binding_revision := nextval('repository.source_configuration_revision_seq');
    ELSIF NEW.binding_revision IS NULL OR NEW.config_revision IS DISTINCT FROM OLD.config_revision THEN
        NEW.binding_revision := nextval('repository.source_configuration_revision_seq');
    ELSE
        NEW.binding_revision := OLD.binding_revision;
        NEW.binding_parent_revision := OLD.binding_parent_revision;
        RETURN NEW;
    END IF;
    SELECT config_revision INTO STRICT NEW.binding_parent_revision FROM repository.github_connections
    WHERE tenant_id=NEW.tenant_id AND github_connection_id=NEW.github_connection_id;
    RETURN NEW;
END;
$$;

-- PostgreSQL orders same-kind triggers alphabetically; run after repository_config_revision.
CREATE TRIGGER zz_repository_binding_revision
    BEFORE INSERT OR UPDATE ON repository.repository_connections
    FOR EACH ROW EXECUTE FUNCTION repository.assign_repository_binding_revision();

CREATE FUNCTION repository.invalidate_bindings_on_github_change()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE affected record;
BEGIN
    IF NEW.config_revision IS NOT DISTINCT FROM OLD.config_revision THEN RETURN NULL; END IF;
    -- Block concurrent FK KEY SHARE (INSERT/rebind) until the parent mutation commits.
    PERFORM 1 FROM repository.github_connections WHERE tenant_id=NEW.tenant_id
        AND github_connection_id=NEW.github_connection_id FOR UPDATE;
    FOR affected IN
        SELECT tenant_id, repository_connection_id FROM repository.repository_connections
        WHERE tenant_id=NEW.tenant_id AND github_connection_id=NEW.github_connection_id
        ORDER BY tenant_id, repository_connection_id FOR NO KEY UPDATE
    LOOP
        UPDATE repository.repository_connections SET binding_revision=NULL
        WHERE tenant_id=affected.tenant_id AND repository_connection_id=affected.repository_connection_id;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER github_binding_revision
    AFTER UPDATE ON repository.github_connections
    FOR EACH ROW EXECUTE FUNCTION repository.invalidate_bindings_on_github_change();
