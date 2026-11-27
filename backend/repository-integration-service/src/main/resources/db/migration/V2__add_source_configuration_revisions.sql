-- LIV-122. Mỗi cấu hình đã thay đổi nhận phiên bản mới, kể cả khi khôi phục giá trị cũ.
CREATE SEQUENCE repository.source_configuration_revision_seq AS bigint NO CYCLE;
GRANT USAGE ON SEQUENCE repository.source_configuration_revision_seq TO ld_s02_app;

ALTER TABLE repository.github_connections
    ADD COLUMN config_revision bigint NOT NULL DEFAULT nextval('repository.source_configuration_revision_seq');
ALTER TABLE repository.repository_connections
    ADD COLUMN config_revision bigint NOT NULL DEFAULT nextval('repository.source_configuration_revision_seq');

-- DEFAULT cấp phiên bản cho dữ liệu V1. Từ đây các trigger quản lý cả INSERT và UPDATE.
ALTER TABLE repository.github_connections ALTER COLUMN config_revision DROP DEFAULT;
ALTER TABLE repository.repository_connections ALTER COLUMN config_revision DROP DEFAULT;

CREATE FUNCTION repository.assign_github_config_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.github_connection_id, NEW.tenant_id, NEW.external_installation_ref, NEW.credential_ref, NEW.status)
           IS NOT DISTINCT FROM
           ROW(OLD.github_connection_id, OLD.tenant_id, OLD.external_installation_ref, OLD.credential_ref, OLD.status) THEN
            NEW.config_revision := OLD.config_revision;
            RETURN NEW;
        END IF;
    END IF;

    NEW.config_revision := nextval('repository.source_configuration_revision_seq');
    RETURN NEW;
END;
$$;

CREATE TRIGGER github_config_revision
    BEFORE INSERT OR UPDATE ON repository.github_connections
    FOR EACH ROW EXECUTE FUNCTION repository.assign_github_config_revision();

CREATE FUNCTION repository.assign_repository_config_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        -- Trigger nhánh yêu cầu cấp phiên bản mới bằng cách đặt config_revision thành NULL.
        IF NEW.config_revision IS NOT NULL
           AND ROW(NEW.repository_connection_id, NEW.tenant_id, NEW.workspace_id, NEW.github_connection_id,
                   NEW.external_repo_id, NEW.default_branch, NEW.status)
           IS NOT DISTINCT FROM
               ROW(OLD.repository_connection_id, OLD.tenant_id, OLD.workspace_id, OLD.github_connection_id,
                   OLD.external_repo_id, OLD.default_branch, OLD.status) THEN
            NEW.config_revision := OLD.config_revision;
            RETURN NEW;
        END IF;
    END IF;

    NEW.config_revision := nextval('repository.source_configuration_revision_seq');
    RETURN NEW;
END;
$$;

CREATE TRIGGER repository_config_revision
    BEFORE INSERT OR UPDATE ON repository.repository_connections
    FOR EACH ROW EXECUTE FUNCTION repository.assign_repository_config_revision();

CREATE FUNCTION repository.invalidate_config_on_tracked_branch_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    affected_connection record;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.tenant_id, NEW.repository_connection_id, NEW.ref_name, NEW.active)
           IS NOT DISTINCT FROM ROW(OLD.tenant_id, OLD.repository_connection_id, OLD.ref_name, OLD.active) THEN
            RETURN NULL;
        END IF;
    END IF;

    -- Khóa theo cùng thứ tự nếu chuyển nhánh giữa hai kết nối. Các truy vấn chạy với RLS của bên gọi.
    FOR affected_connection IN
        SELECT tenant_id, repository_connection_id
        FROM repository.repository_connections
        WHERE (tenant_id, repository_connection_id) IN (
            (OLD.tenant_id, OLD.repository_connection_id),
            (NEW.tenant_id, NEW.repository_connection_id)
        )
        ORDER BY tenant_id, repository_connection_id
        FOR NO KEY UPDATE
    LOOP
        UPDATE repository.repository_connections
        SET config_revision = NULL
        WHERE tenant_id = affected_connection.tenant_id
          AND repository_connection_id = affected_connection.repository_connection_id;
    END LOOP;

    RETURN NULL;
END;
$$;

CREATE TRIGGER tracked_branch_config_revision
    AFTER INSERT OR UPDATE OR DELETE ON repository.tracked_branches
    FOR EACH ROW EXECUTE FUNCTION repository.invalidate_config_on_tracked_branch_change();
