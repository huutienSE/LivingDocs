package com.livingdocs.client;

import com.livingdocs.exception.SourceUnavailableException;
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
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(requests);
    }

    static JsonNode readBody(ClientHttpResponse response, ObjectMapper json) throws IOException {
        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new SourceUnavailableException("Phản hồi GitHub vượt quá kích thước cho phép.");
        }
        return json.readTree(body);
    }
}
