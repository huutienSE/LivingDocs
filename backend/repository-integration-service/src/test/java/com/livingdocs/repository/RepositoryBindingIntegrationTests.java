package com.livingdocs.repository;

import com.livingdocs.exception.SourceApiException;
import com.livingdocs.model.SourceScope;
import com.livingdocs.service.RepositoryBindingService;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL integration for the S01 handoff; never reads another service's tables. */
@SpringBootTest(properties={"spring.config.import=","livingdocs.source.lookup.github-ready-statuses=CONNECTED"})
@ActiveProfiles("db")
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:postgresql:.*")
class RepositoryBindingIntegrationTests {
    @Autowired RepositoryBindingService bindings;
    @Autowired javax.sql.DataSource data;
    private UUID tenant,workspace,repository,github;
    @BeforeEach void seed() throws Exception {
        tenant=UUID.randomUUID(); workspace=UUID.randomUUID(); repository=UUID.randomUUID(); github=UUID.randomUUID();
        try(var db=connection()) {
            try(var q=db.prepareStatement("insert into repository.github_connections(github_connection_id,tenant_id,external_installation_ref,credential_ref,status) values (?,?,'151','fixture.pem','CONNECTED')")) {
                q.setObject(1,github);q.setObject(2,tenant);q.executeUpdate();
            }
            try(var q=db.prepareStatement("insert into repository.repository_connections(repository_connection_id,tenant_id,workspace_id,github_connection_id,external_repo_id,status) values (?,?,?,?,'151','ACTIVE')")) {
                q.setObject(1,repository);q.setObject(2,tenant);q.setObject(3,workspace);q.setObject(4,github);q.executeUpdate();
            }
            db.commit();
        }
    }
    @Test void everyCommittedAuthorityChangeInvalidatesThePreviouslyVerifiedGrantRevision() throws Exception {
        long initial=read();
        update("update repository.repository_connections set status='DISCONNECTED' where repository_connection_id=?",repository);
        assertEquals("CONNECTION_INACTIVE",assertThrows(SourceApiException.class,()->read()).getError().name());
        update("update repository.repository_connections set status='ACTIVE' where repository_connection_id=?",repository);
        long restored=read(); assertTrue(restored>initial);
        update("update repository.github_connections set credential_ref='rotated-fixture.pem' where github_connection_id=?",github);
        assertTrue(read()>restored);
        assertEquals("UNKNOWN_CONNECTION",assertThrows(SourceApiException.class,()->bindings.verify(new SourceScope(tenant,UUID.randomUUID()),repository)).getError().name());
        assertEquals("UNKNOWN_CONNECTION",assertThrows(SourceApiException.class,()->bindings.verify(new SourceScope(UUID.randomUUID(),workspace),repository)).getError().name());
    }
    @Test void githubMutationHoldsTheRepositoryLockSoOppositeCommitOrderCannotHideAChangedBinding() throws Exception {
        long original=read();
        try(var githubTransaction=connection();var repositoryTransaction=connection()) {
            try(var change=githubTransaction.prepareStatement("update repository.github_connections set credential_ref='changed.pem' where github_connection_id=?")) {
                change.setObject(1,github);assertEquals(1,change.executeUpdate());
            }
            assertEquals(original,read(),"Uncommitted authority must not become evidence");
            var locked=assertThrows(java.sql.SQLException.class,()->{
                try(var q=repositoryTransaction.prepareStatement("select repository_connection_id from repository.repository_connections where repository_connection_id=? for no key update nowait")) {
                    q.setObject(1,repository);q.executeQuery();
                }
            });
            assertEquals("55P03",locked.getSQLState());repositoryTransaction.rollback();
            githubTransaction.commit();
        }
        long githubCommitted=read();assertTrue(githubCommitted>original);
        update("update repository.repository_connections set default_branch='different' where repository_connection_id=?",repository);
        assertTrue(read()>githubCommitted);
    }
    @Test void aChildOutsideARepeatableReadSnapshotCannotKeepAnOldPositiveBindingProof() throws Exception {
        UUID inserted=UUID.randomUUID();
        try(var olderSnapshot=connection(true)) {
            try(var q=olderSnapshot.prepareStatement("select config_revision from repository.github_connections where github_connection_id=?")) {
                q.setObject(1,github);q.executeQuery();
            }
            try(var child=connection();var q=child.prepareStatement("insert into repository.repository_connections(repository_connection_id,tenant_id,workspace_id,github_connection_id,external_repo_id,status) values (?,?,?,?,'152','ACTIVE')")) {
                q.setObject(1,inserted);q.setObject(2,tenant);q.setObject(3,workspace);q.setObject(4,github);q.executeUpdate();child.commit();
            }
            long previous=bindings.verify(new SourceScope(tenant,workspace),inserted).getBindingRevision();
            try(var q=olderSnapshot.prepareStatement("update repository.github_connections set credential_ref='changed-parent.pem' where github_connection_id=?")) {
                q.setObject(1,github);q.executeUpdate();
            }
            olderSnapshot.commit();
            assertEquals("CONFIG_CHANGED",assertThrows(SourceApiException.class,()->bindings.verify(new SourceScope(tenant,workspace),inserted)).getError().name());
            update("update repository.repository_connections set default_branch='reconnected' where repository_connection_id=?",inserted);
            assertTrue(bindings.verify(new SourceScope(tenant,workspace),inserted).getBindingRevision()>previous);
        }
    }
    private long read() { return bindings.verify(new SourceScope(tenant,workspace),repository).getBindingRevision(); }
    private Connection connection() throws Exception {
        return connection(false);
    }
    private Connection connection(boolean repeatable) throws Exception {
        var connection=data.getConnection();
        if(repeatable)connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try(var q=connection.prepareStatement("select set_config('app.tenant_id',?,true)")){q.setString(1,tenant.toString());q.execute();}
        return connection;
    }
    private void update(String sql,UUID id) throws Exception {
        try(var connection=connection();var q=connection.prepareStatement(sql)){q.setObject(1,id);assertEquals(1,q.executeUpdate());connection.commit();}
    }
}
