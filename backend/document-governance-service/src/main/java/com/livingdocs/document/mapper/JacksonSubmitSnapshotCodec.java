package com.livingdocs.document.mapper;
import com.livingdocs.document.model.SubmitResult;
import com.livingdocs.document.service.SubmitSnapshotCodec;
import com.livingdocs.document.exception.SubmitException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

@Component 
@RequiredArgsConstructor
public class JacksonSubmitSnapshotCodec implements SubmitSnapshotCodec {
    private final JsonMapper json;
    public String encode(SubmitResult r) { try { return json.writeValueAsString(r); } catch (RuntimeException e) { throw failure(); } }
    public SubmitResult decode(String s) { try { return json.readValue(s, SubmitResult.class); } catch (RuntimeException e) { throw failure(); } }
    private SubmitException failure() { return new SubmitException("PERSISTENCE_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "Submit receipt could not be processed."); }
}
