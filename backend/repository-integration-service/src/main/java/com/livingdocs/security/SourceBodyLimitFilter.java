package com.livingdocs.security;

import com.livingdocs.exception.SourceApiError;
import com.livingdocs.exception.SourceApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/** Đọc tối đa giới hạn + 1 byte, kể cả khi request dùng chunked/không có Content-Length. */
public final class SourceBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 16 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !"POST".equals(request.getMethod()) ||
                !("/internal/v1/source/resolve".equals(path) || "/internal/v1/source/validate".equals(path)
                        || "/internal/v1/repository-bindings/verify".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            throw new SourceApiException(SourceApiError.PAYLOAD_TOO_LARGE);
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        } catch (IOException failure) {
            // Bộ xử lý toàn cục chuyển lỗi đọc thân yêu cầu thành Problem Details.
            throw new SourceApiException(SourceApiError.INVALID_REQUEST);
        }
        if (body.length > MAX_BYTES) {
            throw new SourceApiException(SourceApiError.PAYLOAD_TOO_LARGE);
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final ServletInputStream input;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            var bytes = new ByteArrayInputStream(body);
            this.input = new ServletInputStream() {
                @Override public int read() { return bytes.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return bytes.read(buffer, offset, length); }
                @Override public boolean isFinished() { return bytes.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous request body");
                }
            };
        }

        @Override public ServletInputStream getInputStream() { return input; }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)); }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
