package com.livingdocs.document;
import com.livingdocs.document.controller.VersionEtags;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;
class SubmitRestartIntegrationTest extends PostgresTestSupport {
    ConfigurableApplicationContext start(){var p=new HashMap<String,Object>(properties());p.put("server.port",0);p.put("livingdocs.submit.access-context-uri",SubmitApiIntegrationTest.uri());p.put("livingdocs.submit.allow-insecure-http",true);return new SpringApplicationBuilder(DocumentGovernanceServiceApplication.class).run(p.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));}
    @Test void receiptSurvivesNewServerAndPool()throws Exception{SubmitApiIntegrationTest.Draft f;String original;try(var app=start()){f=SubmitApiIntegrationTest.draft(true);var r=SubmitApiIntegrationTest.post(((WebServerApplicationContext)app).getWebServer().getPort(),f,SubmitApiIntegrationTest.token(f),"{}","restart",VersionEtags.format(f.version(),0));SubmitApiIntegrationTest.expect(r,200,"DOCUMENT_SUBMITTED");original=r.body();}try(var app=start()){var r=SubmitApiIntegrationTest.post(((WebServerApplicationContext)app).getWebServer().getPort(),f,SubmitApiIntegrationTest.token(f),"{}","restart",VersionEtags.format(f.version(),0));SubmitApiIntegrationTest.expect(r,200,"DOCUMENT_SUBMITTED");assertThat(r.body()).isEqualTo(original);assertThat(SubmitApiIntegrationTest.scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?",f.version())).isEqualTo(1L);}}
}
