package com.livingdocs.document;

import com.livingdocs.document.controller.VersionEtags;
import com.livingdocs.document.entity.DocumentAuditEntity;
import com.livingdocs.document.repository.DocumentAuditJpaRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;

/** Real S04 HTTP/JWKS/PostgreSQL; API-038 is a component-test provider here. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="springdoc.api-docs.enabled=true")
class SubmitApiIntegrationTest extends PostgresTestSupport {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Map<UUID,Object> CONTEXT=new ConcurrentHashMap<>();
    static final Map<UUID,Integer> AUTH_STATUS=new ConcurrentHashMap<>();
    static final HttpServer AUTH=startAuthority();
    @LocalServerPort int port;
    @MockitoSpyBean DocumentAuditJpaRepository audits;
    @MockitoSpyBean com.livingdocs.document.repository.DocumentSubmitRepository drafts;
    @DynamicPropertySource static void authority(DynamicPropertyRegistry r) { r.add("livingdocs.submit.access-context-uri",SubmitApiIntegrationTest::uri); r.add("livingdocs.submit.allow-insecure-http",()->true); }
    static String uri() { return "http://127.0.0.1:"+AUTH.getAddress().getPort()+"/context"; }
    static HttpServer startAuthority() {
        try { var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/context",e->{
            try { var token=SignedJWT.parse(e.getRequestHeaders().getFirst("Authorization").substring(7)); var identity=UUID.fromString(token.getJWTClaimsSet().getSubject()); var bytes=JSON.writeValueAsBytes(CONTEXT.getOrDefault(identity,Map.of())); e.getResponseHeaders().set("Content-Type","application/json"); e.sendResponseHeaders(AUTH_STATUS.getOrDefault(identity,200),bytes.length); try(var out=e.getResponseBody()){out.write(bytes);} } catch(Exception x){e.close();}
        }); server.start(); Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0))); return server; } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    record Draft(UUID tenant,UUID workspace,UUID repo,UUID doc,UUID version,UUID actor){}
    static Draft draft(boolean bound) throws Exception {
        var f=new Draft(UUID.randomUUID(),UUID.randomUUID(),bound?UUID.randomUUID():null,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()); var collection=UUID.randomUUID();
        sql("INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES (?,?,?,'Submit')",collection,f.tenant,f.workspace);
        sql("INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES (?,?,?,?,'Draft','test')",f.doc,f.tenant,collection,f.repo);
        sql("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,source_sha,creator_actor_ref,source_target_kind,source_target_ref,source_config_revision,source_captured_at) VALUES (?,?,?,1,'DRAFT',?,'fixture','TRACKED_BRANCH','refs/heads/main','1',CURRENT_TIMESTAMP)",f.version,f.tenant,f.doc,bound?"a".repeat(40):null);
        sql("INSERT INTO document.document_evidence_refs(tenant_id,document_version_id,evidence_type,artifact_ref) VALUES (?,?,'CONTENT','test://draft')",f.tenant,f.version);
        sql("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,editor_identity_id) VALUES (?,?,1,'test://content',?)",f.tenant,f.version,f.actor);
        context(f,f.tenant,f.workspace,"DEVELOPER",true); return f;
    }
    static void context(Draft f,UUID tenant,UUID ws,String role,boolean permission){CONTEXT.put(f.actor,Map.of("code","ACCESS_CONTEXT_LOADED","data",Map.of("identityId",f.actor,"tenantId",tenant,"authzRevision",1,"evaluatedAt",Instant.now().toString(),"workspaceGrants",List.of(Map.of("workspaceId",ws,"roles",List.of(role),"permissions",permission?List.of("WORKSPACE_VIEW","DOCUMENT_AUTHOR"):List.of("WORKSPACE_VIEW"),"repositoryIds",f.repo==null?List.of():List.of(f.repo))))));}
    static void sql(String s,Object... args)throws Exception{ReviewApiIntegrationTest.execute(s,args);}
    static Object scalar(String s,Object... args)throws Exception{return ReviewApiIntegrationTest.scalar(s,args);}
    static String token(Draft f)throws Exception{var c=new JWTClaimsSet.Builder().issuer(ISSUER).audience("s04-test").subject(f.actor.toString()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build();var t=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),c);t.sign(new RSASSASigner(KEY));return t.serialize();}
    static HttpResponse<String> post(int port,Draft f,String token,String payload,String key,String etag)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/document-versions/"+f.version+"/submit")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload));if(token!=null)r.header("Authorization","Bearer "+token);if(key!=null)r.header("Idempotency-Key",key);if(etag!=null)r.header("If-Match",etag);return HttpClient.newHttpClient().send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> submit(Draft f,String body,String key,long rev)throws Exception{return post(port,f,token(f),body,key,VersionEtags.format(f.version,rev));}
    static void expect(HttpResponse<String> r,int status,String code){assertThat(r.statusCode()).as(r.body()).isEqualTo(status);assertThat(JSON.readTree(r.body()).path("code").asString()).isEqualTo(code);}
    static void unchanged(Draft f)throws Exception{assertThat(scalar("SELECT state FROM document.document_versions WHERE document_version_id=?",f.version)).isEqualTo("DRAFT");assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?",f.version)).isEqualTo(0L);assertThat(scalar("SELECT count(*) FROM document.submit_requests WHERE document_version_id=?",f.version)).isEqualTo(0L);assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?",f.version)).isEqualTo(0L);}
    @Test void submitReplayAndConflicts()throws Exception{
        var f=draft(false);var first=submit(f,"{}","one",0);expect(first,200,"DOCUMENT_SUBMITTED");assertThat(JSON.readTree(first.body()).path("data").path("reviewStage").asString()).isEqualTo("STAFF_REVIEW");assertThat(first.headers().firstValue("ETag")).contains(VersionEtags.format(f.version,1));assertThat(submit(f,"{}","one",0).body()).isEqualTo(first.body());expect(submit(f,"{\"note\":\"other\"}","one",0),409,"IDEMPOTENCY_CONFLICT");expect(submit(f,"{}","two",0),412,"REVISION_CONFLICT");expect(submit(f,"{}","two",1),409,"STATE_TRANSITION_FORBIDDEN");assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?",f.version)).isEqualTo(1L);assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?",f.version)).isEqualTo(0L);
    }
    @Test void rolePermissionWorkspaceAndTenant()throws Exception{var f=draft(false);context(f,f.tenant,f.workspace,"STAFF",true);expect(submit(f,"{}","a",0),403,"FORBIDDEN_SCOPE");context(f,f.tenant,f.workspace,"DEVELOPER",false);expect(submit(f,"{}","a",0),403,"FORBIDDEN_SCOPE");context(f,f.tenant,UUID.randomUUID(),"DEVELOPER",true);expect(submit(f,"{}","a",0),403,"FORBIDDEN_SCOPE");context(f,UUID.randomUUID(),f.workspace,"DEVELOPER",true);expect(submit(f,"{}","a",0),404,"DOCUMENT_VERSION_NOT_FOUND");unchanged(f);}
    @Test void evidenceAndPersistedSourceBindingAreRequired()throws Exception{var f=draft(false);sql("DELETE FROM document.document_evidence_refs WHERE document_version_id=?",f.version);expect(submit(f,"{}","a",0),422,"SUBMIT_EVIDENCE_REQUIRED");unchanged(f);var b=draft(true);sql("UPDATE document.document_versions SET source_target_ref=NULL WHERE document_version_id=?",b.version);expect(submit(b,"{}","a",0),422,"SOURCE_EVIDENCE_INSUFFICIENT");unchanged(b);var c=draft(true);var result=submit(c,"{}","a",0);expect(result,200,"DOCUMENT_SUBMITTED");assertThat(JSON.readTree(result.body()).path("data").path("sourceBinding").path("headSha").asString()).isEqualTo("a".repeat(40));}
    @Test void headersAndBody()throws Exception{var f=draft(false);expect(post(port,f,token(f),"{}","a",null),428,"PRECONDITION_REQUIRED");expect(post(port,f,token(f),"{}",null,VersionEtags.format(f.version,0)),400,"INVALID_REQUEST");expect(post(port,f,null,"{}","a",VersionEtags.format(f.version,0)),401,"UNAUTHENTICATED");expect(submit(f,"{\"state\":\"APPROVED\"}","a",0),400,"INVALID_REQUEST");unchanged(f);}
    @Test void jpaEvidenceQueryRejectsBlankAndForeignEvidenceButAcceptsSourceOnly() throws Exception {
        var empty=draft(false);
        sql("UPDATE document.document_evidence_refs SET artifact_ref='   ' WHERE document_version_id=?",empty.version);
        var foreign=draft(false);
        expect(submit(empty,"{}","blank",0),422,"SUBMIT_EVIDENCE_REQUIRED"); unchanged(empty);
        sql("UPDATE document.document_evidence_refs SET artifact_ref=NULL,source_ref=' source://valid ' WHERE document_version_id=?",empty.version);
        expect(submit(empty,"{}","source",0),200,"DOCUMENT_SUBMITTED");
        unchanged(foreign);
    }
    @Test void concurrentRetries()throws Exception{var f=draft(false);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var calls=new ArrayList<Callable<HttpResponse<String>>>();for(int i=0;i<4;i++)calls.add(()->submit(f,"{}","same",0));for(var r:pool.invokeAll(calls))expect(r.get(),200,"DOCUMENT_SUBMITTED");}assertThat(scalar("SELECT count(*) FROM document.submit_requests WHERE document_version_id=?",f.version)).isEqualTo(1L);}
    @Test void auditFailureRollsBack()throws Exception{var f=draft(false);doThrow(new DataIntegrityViolationException("test failure")).when(audits).saveAndFlush(argThat((DocumentAuditEntity a)->a!=null&&a.getVersionId().equals(f.version)));expect(submit(f,"{}","a",0),500,"PERSISTENCE_ERROR");unchanged(f);}
    @Test void upstreamAuthenticationFailureIncludesBearerChallenge() throws Exception {
        var f=draft(false); AUTH_STATUS.put(f.actor,401);
        var response=submit(f,"{}","unauthenticated",0);
        expect(response,401,"UNAUTHENTICATED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        unchanged(f);
    }
    @Test void databaseLockFailuresUseConflictResponsesAndRollBack() throws Exception {
        com.livingdocs.document.repository.DocumentSubmitRepository target =
            org.springframework.test.util.AopTestUtils.getUltimateTargetObject(drafts);
        var optimistic=draft(false);
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("test-only revision conflict"))
            .when(target).submit(argThat(v -> v!=null && v.id().equals(optimistic.version)));
        expect(submit(optimistic,"{}","optimistic",0),412,"REVISION_CONFLICT"); unchanged(optimistic);
        var pessimistic=draft(false);
        doThrow(new org.springframework.dao.PessimisticLockingFailureException("test-only lock conflict"))
            .when(target).submit(argThat(v -> v!=null && v.id().equals(pessimistic.version)));
        expect(submit(pessimistic,"{}","pessimistic",0),409,"CONCURRENT_REVIEW"); unchanged(pessimistic);
    }
    @Test void returnedDraftKeepsHistory()throws Exception{var f=draft(false);sql("INSERT INTO document.review_decisions(tenant_id,document_version_id,reviewer_identity_id,decision,reason,round_no) VALUES (?,?,?,'RETURN_FOR_REVISION','Revise',1)",f.tenant,f.version,UUID.randomUUID());expect(submit(f,"{}","resubmit",0),200,"DOCUMENT_SUBMITTED");assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?",f.version)).isEqualTo(1L);assertThat(scalar("SELECT count(*) FROM document.content_revisions WHERE document_version_id=?",f.version)).isEqualTo(1L);}
    @Test void runtimeContract() throws Exception {
        var f=draft(false);
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).header("Authorization","Bearer "+token(f)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        var runtime=JSON.readTree(r.body());
        var loader=new org.yaml.snakeyaml.LoaderOptions(); loader.setMaxAliasesForCollections(1000);
        var spec=JSON.readTree(JSON.writeValueAsString(new org.yaml.snakeyaml.Yaml(loader).load(java.nio.file.Files.readString(java.nio.file.Path.of("../../contracts/openapi/s04-document.yaml")))));
        String path="/api/v1/document-versions/{versionId}/submit";
        var op=runtime.path("paths").path(path).path("post"); var expected=spec.path("paths").path(path).path("post");
        assertThat(op.path("operationId").asString()).isEqualTo(expected.path("operationId").asString());
        assertThat(op.path("responses").propertyNames()).containsExactlyInAnyOrderElementsOf(expected.path("responses").propertyNames());
        for(String name:List.of("If-Match","Idempotency-Key")) {
            boolean required=false;
            for(var parameter:op.path("parameters")) if(name.equals(parameter.path("name").asString())) required=parameter.path("required").asBoolean();
            assertThat(required).as(name).isTrue();
        }
        var request=resolve(runtime,op.path("requestBody").path("content").path("application/json").path("schema"));
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("properties").propertyNames()).containsExactly("note");
        assertThat(request.path("properties").path("note").path("maxLength").asInt()).isEqualTo(2000);
        assertThat(request.path("properties").path("note").path("nullable").asBoolean()).isTrue();
        var success=op.path("responses").path("200");
        assertThat(success.path("headers").propertyNames()).containsExactlyInAnyOrderElementsOf(expected.path("responses").path("200").path("headers").propertyNames());
        var envelope=resolve(runtime,success.path("content").path("application/json").path("schema"));
        assertThat(envelope.path("properties").propertyNames()).containsExactlyInAnyOrder("code","message","data");
        var version=resolve(runtime,envelope.path("properties").path("data"));
        assertThat(version.path("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(spec.path("components").path("schemas").path("DocumentVersionView").path("properties").propertyNames());
        for(String status:expected.path("responses").propertyNames()) if(!"200".equals(status)) {
            var error=op.path("responses").path(status);
            assertThat(error.path("content").propertyNames()).containsExactly("application/problem+json");
            if ("401".equals(status)) assertThat(error.path("headers").has("WWW-Authenticate")).isTrue();
            var problem=resolve(runtime,error.path("content").path("application/problem+json").path("schema"));
            assertThat(problem.path("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(spec.path("components").path("schemas").path("Problem").path("properties").propertyNames());
            var codes=new HashSet<String>();
            for(var code:problem.path("properties").path("code").path("enum")) codes.add(code.asString());
            assertThat(codes).contains("FORBIDDEN_SCOPE","SUBMIT_EVIDENCE_REQUIRED","UNAUTHENTICATED","REVISION_CONFLICT","CONCURRENT_REVIEW");
        }
    }
    private tools.jackson.databind.JsonNode resolve(tools.jackson.databind.JsonNode document,tools.jackson.databind.JsonNode schema) {
        while(schema.has("$ref")) schema=document.at(schema.path("$ref").asString().substring(1));
        return schema;
    }
    @Test void revokedPermissionDeniesReplayAndMalformedAuthorityDeniesNewRequest() throws Exception {
        var f=draft(false); expect(submit(f,"{}","same",0),200,"DOCUMENT_SUBMITTED");
        context(f,f.tenant,f.workspace,"DEVELOPER",false);
        expect(submit(f,"{}","same",0),403,"FORBIDDEN_SCOPE");
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?",f.version)).isEqualTo(1L);
        var other=draft(false); CONTEXT.remove(other.actor);
        expect(submit(other,"{}","new",0),503,"AUTHORIZATION_UNAVAILABLE"); unchanged(other);
    }
    @Test void repositoryGrantIsRequiredAndPermissionsCannotBeCombinedAcrossWorkspaces() throws Exception {
        var f=draft(true);
        CONTEXT.put(f.actor,Map.of("code","ACCESS_CONTEXT_LOADED","data",Map.of("identityId",f.actor,"tenantId",f.tenant,"authzRevision",1,"evaluatedAt",Instant.now().toString(),"workspaceGrants",List.of(Map.of("workspaceId",f.workspace,"roles",List.of("DEVELOPER"),"permissions",List.of("WORKSPACE_VIEW","DOCUMENT_AUTHOR"),"repositoryIds",List.of())))));
        expect(submit(f,"{}","no-grant",0),403,"FORBIDDEN_SCOPE"); unchanged(f);
        var other=draft(false);
        var grants=List.of(Map.of("workspaceId",other.workspace,"roles",List.of("DEVELOPER"),"permissions",List.of("WORKSPACE_VIEW"),"repositoryIds",List.of()),Map.of("workspaceId",UUID.randomUUID(),"roles",List.of("DEVELOPER"),"permissions",List.of("WORKSPACE_VIEW","DOCUMENT_AUTHOR"),"repositoryIds",List.of()));
        CONTEXT.put(other.actor,Map.of("code","ACCESS_CONTEXT_LOADED","data",Map.of("identityId",other.actor,"tenantId",other.tenant,"authzRevision",1,"evaluatedAt",Instant.now().toString(),"workspaceGrants",grants)));
        expect(submit(other,"{}","split-grants",0),403,"FORBIDDEN_SCOPE"); unchanged(other);
    }
    @Test void requestValidationAndFutureOrMalformedSourceProvenanceDoNotMutate() throws Exception {
        var f=draft(false);
        expect(post(port,f,token(f),"{}","a","W/"+VersionEtags.format(f.version,0)),400,"INVALID_IF_MATCH");
        expect(post(port,f,token(f),"{}","a",VersionEtags.format(UUID.randomUUID(),0)),412,"REVISION_CONFLICT");
        expect(submit(f,"{\"note\":\""+"x".repeat(2001)+"\"}","a",0),400,"INVALID_REQUEST"); unchanged(f);
        var b=draft(true);sql("UPDATE document.document_versions SET source_comparison_sha='bad' WHERE document_version_id=?",b.version);
        expect(submit(b,"{}","a",0),422,"SOURCE_EVIDENCE_INSUFFICIENT"); unchanged(b);
        var c=draft(true);sql("UPDATE document.document_versions SET source_captured_at=CURRENT_TIMESTAMP+INTERVAL '1 day' WHERE document_version_id=?",c.version);
        expect(submit(c,"{}","a",0),422,"SOURCE_EVIDENCE_INSUFFICIENT"); unchanged(c);
    }

    @Test void submitReturnAndResubmitUseTheSameVersionAndPreserveHistory() throws Exception {
        var f=draft(false); var first=submit(f,"{}","first",0); expect(first,200,"DOCUMENT_SUBMITTED");
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor,ReviewAuthorityTestServer.context(f.actor,f.tenant,List.of(ReviewAuthorityTestServer.grant(f.workspace,List.of("STAFF"),List.of("WORKSPACE_VIEW","DOCUMENT_STAFF_REVIEW"),List.of()))));
        var returned=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/document-versions/"+f.version+"/staff-decisions"))
            .header("Authorization","Bearer "+token(f)).header("Content-Type","application/json").header("Idempotency-Key","return").header("If-Match",VersionEtags.format(f.version,1))
            .POST(HttpRequest.BodyPublishers.ofString("{\"decision\":\"RETURN_FOR_REVISION\",\"feedback\":\"Revise\"}")).build(),HttpResponse.BodyHandlers.ofString());
        expect(returned,200,"STAFF_REVIEW_RECORDED");
        expect(submit(f,"{}","second",2),200,"DOCUMENT_SUBMITTED");
        assertThat(scalar("SELECT review_stage FROM document.document_versions WHERE document_version_id=?",f.version)).isEqualTo("STAFF_REVIEW");
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?",f.version)).isEqualTo(3L);
        assertThat(scalar("SELECT count(*) FROM document.submit_requests WHERE document_version_id=?",f.version)).isEqualTo(2L);
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?",f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?",f.version)).isEqualTo(3L);
        assertThat(submit(f,"{}","first",0).body()).isEqualTo(first.body());
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?",f.version)).isEqualTo(3L);
    }

}
