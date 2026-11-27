package com.livingdocs.client;

import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import com.livingdocs.model.SourceRequestDeadline;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Cấu hình truyền tải dùng chung cho các yêu cầu GitHub của S02. */
final class GitHubHttp {
    static final String BASE_URL = "https://api.github.com";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private GitHubHttp() {
    }

    static RestClient.Builder clientBuilder() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return RestClient.builder().requestFactory((uri, method) -> {
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
            requests.setReadTimeout(SourceRequestDeadline.limit(Duration.ofSeconds(2)));
            return requests.createRequest(uri, method);
        });
    }

    static JsonNode readBody(ClientHttpResponse response, ObjectMapper json) throws IOException {
        SourceRequestDeadline.check();
        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        SourceRequestDeadline.check();
        if (body.length > MAX_RESPONSE_BYTES) {
            // Phản hồi GitHub quá lớn nên S02 không có đủ bằng chứng để xác minh nguồn.
            throw SourceApiException.unavailable(UnavailableReason.GITHUB_RESPONSE_TOO_LARGE);
        }
        return json.readTree(body);
    }
}
