package com.livingdocs.security;

import com.livingdocs.client.GitHubAuthorizationIo;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.exception.GitHubAuthorizationProblems;
import com.livingdocs.model.GitHubAuthorizationError;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wait for HTTP/2 END_STREAM using container callbacks; never give a request stream to a worker. */
public final class GitHubAuthorizationBodyGuard {
    private static final String EMPTY_BODY = GitHubAuthorizationBodyGuard.class.getName() + ".empty";
    private static final String JSON_BODY = GitHubAuthorizationBodyGuard.class.getName() + ".json";
    private final GitHubAuthorizationProblems problems;
    private final GitHubAuthorizationIo io;

    public GitHubAuthorizationBodyGuard(GitHubAuthorizationProblems problems, GitHubAuthorizationIo io) {
        this.problems = problems;
        this.io = io;
    }

    public boolean ready(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (Boolean.TRUE.equals(request.getAttribute(EMPTY_BODY)) || request.getContentLengthLong() == 0) return true;
        var input = request.getInputStream();
        if (input.isFinished()) return true;
        begin(request, response, input, false);
        return false;
    }

    public HttpServletRequest json(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getAttribute(JSON_BODY) instanceof byte[] bytes) return cached(request, bytes);
        if (request.getContentLengthLong() > 4096) throw new GitHubAuthorizationException(GitHubAuthorizationError.PAYLOAD_TOO_LARGE);
        var input = request.getInputStream();
        if (request.getContentLengthLong() == 0 || input.isFinished()) return cached(request, new byte[0]);
        begin(request, response, input, true);
        return null;
    }

    private void begin(HttpServletRequest request, HttpServletResponse response, jakarta.servlet.ServletInputStream input, boolean json) throws IOException {
        if (!request.isAsyncSupported()) throw new GitHubAuthorizationException(GitHubAuthorizationError.INVALID_REQUEST);
        var permit = io.acquire(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE);
        final AsyncContext async;
        try { async = request.startAsync(request, response); }
        catch (RuntimeException unsupported) { permit.close(); throw new GitHubAuthorizationException(GitHubAuthorizationError.INVALID_REQUEST); }
        var completed = new AtomicBoolean();
        var buffer = new java.io.ByteArrayOutputStream();
        class BodyListener implements ReadListener, AsyncListener {
            private void reject(GitHubAuthorizationError error) throws IOException {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    if (!response.isCommitted()) problems.write(error, request, response);
                } finally { permit.close(); async.complete(); }
            }
            @Override public void onDataAvailable() throws IOException {
                while (!completed.get() && input.isReady() && !input.isFinished()) {
                    int next = input.read();
                    if (next == -1) return;
                    if (!json) { reject(GitHubAuthorizationError.INVALID_REQUEST); return; }
                    if (buffer.size() == 4096) { reject(GitHubAuthorizationError.PAYLOAD_TOO_LARGE); return; }
                    buffer.write(next);
                }
            }
            @Override public void onAllDataRead() {
                if (!completed.compareAndSet(false, true)) return;
                request.setAttribute(json ? JSON_BODY : EMPTY_BODY, json ? buffer.toByteArray() : Boolean.TRUE);
                permit.close();
                async.dispatch();
            }
            @Override public void onError(Throwable failure) {
                try { reject(GitHubAuthorizationError.INVALID_REQUEST); } catch (IOException ignored) { /* Container owns the failed response. */ }
            }
            @Override public void onTimeout(AsyncEvent event) throws IOException { reject(GitHubAuthorizationError.INVALID_REQUEST); }
            @Override public void onError(AsyncEvent event) throws IOException { reject(GitHubAuthorizationError.INVALID_REQUEST); }
            @Override public void onComplete(AsyncEvent event) { completed.set(true); permit.close(); }
            @Override public void onStartAsync(AsyncEvent event) { }
        }
        var listener = new BodyListener();
        async.setTimeout(1000);
        async.addListener(listener);
        try { input.setReadListener(listener); }
        catch (RuntimeException unsupported) { listener.reject(GitHubAuthorizationError.INVALID_REQUEST); }
    }

    private static HttpServletRequest cached(HttpServletRequest request, byte[] bytes) {
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public java.io.BufferedReader getReader() {
                return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                var buffer = new java.io.ByteArrayInputStream(bytes);
                return new jakarta.servlet.ServletInputStream() {
                    @Override public int read() { return buffer.read(); }
                    @Override public boolean isFinished() { return buffer.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        try { if (!isFinished()) listener.onDataAvailable(); if (isFinished()) listener.onAllDataRead(); }
                        catch (IOException failed) { listener.onError(failed); }
                    }
                };
            }
        };
    }
}
