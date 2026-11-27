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
        if (!request.isAsyncSupported()) throw new GitHubAuthorizationException(GitHubAuthorizationError.INVALID_REQUEST);
        var permit = io.acquire(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE);
        final AsyncContext async;
        try { async = request.startAsync(request, response); }
        catch (RuntimeException unsupported) { permit.close(); throw new GitHubAuthorizationException(GitHubAuthorizationError.INVALID_REQUEST); }
        var completed = new AtomicBoolean();
        class BodyListener implements ReadListener, AsyncListener {
            private void reject() throws IOException {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    if (!response.isCommitted()) problems.write(GitHubAuthorizationError.INVALID_REQUEST, request, response);
                } finally { permit.close(); async.complete(); }
            }
            @Override public void onDataAvailable() throws IOException {
                if (completed.get() || !input.isReady()) return;
                if (input.read() != -1) reject();
            }
            @Override public void onAllDataRead() {
                if (!completed.compareAndSet(false, true)) return;
                request.setAttribute(EMPTY_BODY, true);
                permit.close();
                async.dispatch();
            }
            @Override public void onError(Throwable failure) {
                try { reject(); } catch (IOException ignored) { /* Container owns the failed response. */ }
            }
            @Override public void onTimeout(AsyncEvent event) throws IOException { reject(); }
            @Override public void onError(AsyncEvent event) throws IOException { reject(); }
            @Override public void onComplete(AsyncEvent event) { completed.set(true); permit.close(); }
            @Override public void onStartAsync(AsyncEvent event) { }
        }
        var listener = new BodyListener();
        async.setTimeout(1000);
        async.addListener(listener);
        try { input.setReadListener(listener); }
        catch (RuntimeException unsupported) { listener.reject(); }
        return false;
    }
}
