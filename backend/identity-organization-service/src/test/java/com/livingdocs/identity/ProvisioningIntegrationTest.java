package com.livingdocs.identity;

import com.livingdocs.identity.provisioning.ProvisioningManifest;
import com.livingdocs.identity.provisioning.ProvisioningService;
import java.util.List;
import java.util.UUID;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("db")
@Testcontainers
class ProvisioningIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("livingdocs").withUsername("postgres").withPassword("postgres")
            .withInitScript("s01-test-schema.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_SCHEMA", () -> "identity");
        registry.add("DB_USERNAME", () -> "ld_operator_test");
        registry.add("DB_PASSWORD", () -> "test_operator_password");
        registry.add("FLYWAY_USERNAME", POSTGRES::getUsername);
        registry.add("FLYWAY_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired ProvisioningService provisioning;
    @Autowired JdbcTemplate runtime;

    @Test
    void operatorEnrollmentScriptValidatesAndBindsNamedLogin() throws Exception {
        UUID actor = identity(admin());
        Path script = Path.of("../../infrastructure/docker/operator-enroll.sql").toAbsolutePath();
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/operator-enroll.sql");
        var result = POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "livingdocs",
                "-v", "operator_login=ld_operator_scripted",
                "-v", "operator_identity_id=" + actor,
                "-v", "reason=Approved named operator enrollment test",
                "-f", "/tmp/operator-enroll.sql");
        assertThat(result.getExitCode()).describedAs(result.getStderr()).isZero();
        assertThat(admin().queryForObject("""
                SELECT identity_id FROM identity.operator_principals
                WHERE db_login_name='ld_operator_scripted' AND status='ACTIVE'
                """, UUID.class)).isEqualTo(actor);
        assertThat(admin().queryForObject("""
                SELECT pg_has_role('ld_operator_scripted', 'ld_s01_provisioner', 'member')
                """, Boolean.class)).isTrue();
        Path disable = Path.of("../../infrastructure/docker/operator-disable.sql").toAbsolutePath();
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(disable), "/tmp/operator-disable.sql");
        var revoked = POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "livingdocs",
                "-v", "operator_login=ld_operator_scripted",
                "-v", "reason=Approved operator revocation test",
                "-f", "/tmp/operator-disable.sql");
        assertThat(revoked.getExitCode()).describedAs(revoked.getStderr()).isZero();
        assertThat(admin().queryForObject("""
                SELECT status FROM identity.operator_principals WHERE db_login_name='ld_operator_scripted'
                """, String.class)).isEqualTo("REVOKED");
        assertThat(admin().queryForObject("""
                SELECT rolcanlogin FROM pg_roles WHERE rolname='ld_operator_scripted'
                """, Boolean.class)).isFalse();
    }

    @Test
    void platformActorIsBoundAndTenantOwnerIsDifferentWithAtomicRevokeAndPendingGrant() throws Exception {
        JdbcTemplate admin = admin();
        UUID operator = identity(admin);
        UUID requester = identity(admin);
        UUID tenant = UUID.randomUUID();
        ProvisioningManifest unmapped = manifest("CREATE_TENANT", UUID.randomUUID(), operator);
        unmapped.setIdentityId(requester);
        unmapped.setRoleCode("TENANT_OWNER");
        unmapped.setName("Unmapped should fail");
        assertThatThrownBy(() -> provisioning.apply(unmapped, digest('0')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping");
        admin.update("INSERT INTO identity.operator_principals(db_login_name, identity_id, status) VALUES ('ld_operator_test', ?, 'ACTIVE')",
                operator);
        admin.update("INSERT INTO identity.operator_principals(db_login_name, identity_id, status) VALUES ('ld_operator_other', ?, 'ACTIVE')",
                requester);
        assertThat(runtime.queryForObject("SELECT count(*) FROM identity.operator_principals",
                Integer.class)).isEqualTo(1);
        bootstrap().queryForObject("SELECT identity.bootstrap_platform_admin(?, ?)", UUID.class,
                operator, "Approved initial platform administrator");
        assertThatThrownBy(() -> bootstrap().queryForObject(
                "SELECT identity.bootstrap_platform_admin(?, ?)", UUID.class,
                requester, "Second bootstrap must not succeed")).isInstanceOf(RuntimeException.class);
        assertThat(runtime.queryForObject("SELECT session_user", String.class)).isEqualTo("ld_operator_test");
        assertThat(runtime.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = session_user", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'identity.tenants'::regclass",
                String.class)).isNotEqualTo("ld_operator_test");

        ProvisioningManifest create = manifest("CREATE_TENANT", tenant, operator);
        create.setIdentityId(requester);
        create.setRoleCode("TENANT_OWNER");
        create.setName("Requester owned tenant");
        ProvisioningManifest forged = manifest("CREATE_TENANT", UUID.randomUUID(), requester);
        forged.setIdentityId(requester);
        forged.setRoleCode("TENANT_OWNER");
        forged.setName("Forged actor");
        assertThatThrownBy(() -> provisioning.apply(forged, digest('1')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThat(provisioning.apply(create, digest('a'))).isEqualTo("APPLIED");
        assertThat(provisioning.apply(create, digest('a'))).isEqualTo("ALREADY_APPLIED");
        assertThatThrownBy(() -> provisioning.apply(create, digest('b')))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(admin.queryForObject("""
                SELECT m.identity_id FROM identity.tenant_memberships m
                JOIN identity.tenant_role_assignments a
                  ON a.tenant_id=m.tenant_id AND a.tenant_membership_id=m.membership_id
                WHERE m.tenant_id=? AND a.role_code='TENANT_OWNER'
                """, UUID.class, tenant)).isEqualTo(requester);
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id=? AND identity_id=?",
                Integer.class, tenant, operator)).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM identity.authorization_audit
                WHERE tenant_id=? AND actor_identity_id=? AND db_actor_name='ld_operator_test'
                """, Integer.class, tenant, operator)).isGreaterThan(0);
        UUID secondTenant = UUID.randomUUID();
        ProvisioningManifest duplicateMember = manifest("CREATE_TENANT", secondTenant, operator);
        duplicateMember.setIdentityId(requester);
        duplicateMember.setRoleCode("TENANT_OWNER");
        duplicateMember.setName("Invalid second active membership");
        assertThatThrownBy(() -> provisioning.apply(duplicateMember, digest('2')))
                .isInstanceOf(RuntimeException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.tenants WHERE tenant_id=?",
                Integer.class, secondTenant)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.provisioning_operations WHERE operation_id=?",
                Integer.class, duplicateMember.getOperationId())).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.authorization_audit WHERE tenant_id=?",
                Integer.class, secondTenant)).isZero();
        admin.update("""
                UPDATE identity.platform_role_assignments
                SET status='REVOKED', ended_at=now() WHERE identity_id=?
                """, operator);
        ProvisioningManifest revokedPlatform = manifest("CREATE_TENANT", UUID.randomUUID(), operator);
        revokedPlatform.setIdentityId(identity(admin));
        revokedPlatform.setRoleCode("TENANT_OWNER");
        revokedPlatform.setName("Revoked platform actor");
        assertThatThrownBy(() -> provisioning.apply(revokedPlatform, digest('3')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Platform permission");
        admin.update("""
                UPDATE identity.platform_role_assignments
                SET status='ACTIVE', ended_at=NULL WHERE identity_id=?
                """, operator);

        // Platform authority cannot administer the new tenant without a separate
        // Tenant Owner assignment. A test fixture grants that assignment explicitly.
        ProvisioningManifest denied = manifest("CREATE_WORKSPACE", tenant, operator);
        denied.setWorkspaceId(UUID.randomUUID());
        denied.setName("Denied");
        assertThatThrownBy(() -> provisioning.apply(denied, digest('c')))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.provisioning_operations WHERE operation_id=?",
                Integer.class, denied.getOperationId())).isZero();
        UUID operatorMember = UUID.randomUUID();
        admin.update("INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES (?,?,?)",
                operatorMember, tenant, operator);
        admin.update("INSERT INTO identity.tenant_role_assignments(tenant_id,tenant_membership_id,role_code) VALUES (?,?,'TENANT_OWNER')",
                tenant, operatorMember);
        UUID workspace = UUID.randomUUID();
        ProvisioningManifest space = manifest("CREATE_WORKSPACE", tenant, operator);
        space.setWorkspaceId(workspace);
        space.setName("Authority");
        provisioning.apply(space, digest('d'));

        // Only a Technical Lead can request a PENDING grant for their own membership.
        ProvisioningManifest member = manifest("ADD_WORKSPACE_MEMBER", tenant, operator);
        member.setWorkspaceId(workspace);
        member.setIdentityId(operator);
        provisioning.apply(member, digest('e'));
        UUID workspaceMember = admin.queryForObject("""
                SELECT workspace_membership_id FROM identity.workspace_memberships
                WHERE tenant_id=? AND identity_id=?
                """, UUID.class, tenant, operator);
        ProvisioningManifest grant = manifest("REQUEST_REPOSITORY_GRANT", tenant, operator);
        grant.setWorkspaceId(workspace);
        grant.setWorkspaceMembershipId(workspaceMember);
        grant.setRepositoryConnectionId(UUID.randomUUID());
        grant.setProvenance("Waiting for authoritative S02 binding verification");
        assertThatThrownBy(() -> provisioning.apply(grant, digest('f')))
                .isInstanceOf(IllegalArgumentException.class);
        ProvisioningManifest lead = manifest("ASSIGN_WORKSPACE_ROLE", tenant, operator);
        lead.setWorkspaceId(workspace);
        lead.setWorkspaceMembershipId(workspaceMember);
        lead.setRoleCode("TECHNICAL_LEAD");
        provisioning.apply(lead, digest('7'));
        provisioning.apply(grant, digest('f'));
        assertThat(admin.queryForObject("SELECT verification_status FROM identity.repository_grants WHERE tenant_id=?",
                String.class, tenant)).isEqualTo("PENDING");

        // Two distinct role rows must not both be revoked after seeing count=2.
        UUID ownerA = admin.queryForObject("""
                SELECT assignment_id FROM identity.tenant_role_assignments
                WHERE tenant_id=? AND tenant_membership_id=? AND role_code='TENANT_OWNER'
                """, UUID.class, tenant, operatorMember);
        UUID ownerB = admin.queryForObject("""
                SELECT a.assignment_id FROM identity.tenant_role_assignments a
                JOIN identity.tenant_memberships m ON m.membership_id=a.tenant_membership_id
                WHERE a.tenant_id=? AND m.identity_id=? AND a.role_code='TENANT_OWNER'
                """, UUID.class, tenant, requester);
        ProvisioningManifest revokeA = manifest("REVOKE_TENANT_ROLE", tenant, operator);
        revokeA.setTargetId(ownerA);
        ProvisioningManifest revokeB = manifest("REVOKE_TENANT_ROLE", tenant, operator);
        revokeB.setTargetId(ownerB);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return outcome(revokeA, digest('8')); });
            var second = pool.submit(() -> { start.await(); return outcome(revokeB, digest('9')); });
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("APPLIED", "REJECTED");
        }
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM identity.tenant_role_assignments a
                JOIN identity.tenant_memberships m ON m.membership_id=a.tenant_membership_id
                WHERE a.tenant_id=? AND a.status='ACTIVE' AND m.status='ACTIVE' AND a.role_code='TENANT_OWNER'
                """, Integer.class, tenant)).isEqualTo(1);
        admin.update("UPDATE identity.operator_principals SET status='REVOKED', revoked_at=now() WHERE db_login_name='ld_operator_test'");
        ProvisioningManifest afterOperatorRevoke = manifest("CREATE_TENANT", UUID.randomUUID(), operator);
        afterOperatorRevoke.setIdentityId(identity(admin));
        afterOperatorRevoke.setRoleCode("TENANT_OWNER");
        afterOperatorRevoke.setName("Revoked operator mapping");
        assertThatThrownBy(() -> provisioning.apply(afterOperatorRevoke, digest('4')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping");
    }

    private String outcome(ProvisioningManifest m, String digest) {
        try { return provisioning.apply(m, digest); }
        catch (RuntimeException ex) { return "REJECTED"; }
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private JdbcTemplate bootstrap() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                "ld_bootstrap_test", "test_bootstrap_password"));
    }

    private UUID identity(JdbcTemplate admin) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO identity.identities(identity_id, email) VALUES (?, ?)",
                id, "operator-test-" + id + "@example.com");
        return id;
    }

    private static ProvisioningManifest manifest(String type, UUID tenant, UUID operator) {
        ProvisioningManifest m = new ProvisioningManifest();
        m.setOperationId(UUID.randomUUID());
        m.setTenantId(tenant);
        m.setOperatorIdentityId(operator);
        m.setType(type);
        m.setReason("Approved scoped authority operation in isolated integration test");
        return m;
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
