package com.livingdocs.service;

import com.livingdocs.model.SourceResolveRequest;
import com.livingdocs.model.SourceResolveResult;
import com.livingdocs.model.SourceScope;

/** Tra SHA đầu nhánh hoặc đầu PR bằng một lần đọc GitHub mới. */
public interface SourceResolveService {
    SourceResolveResult resolve(SourceScope scope, SourceResolveRequest request);
}
