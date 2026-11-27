package com.livingdocs.service;

import com.livingdocs.model.SourceScope;
import com.livingdocs.model.SourceValidationRequest;
import com.livingdocs.model.SourceValidationResult;

/** Đối chiếu tham chiếu và SHA đã gắn với tài liệu với đầu nguồn hiện hành. */
public interface SourceValidationService {
    SourceValidationResult validate(SourceScope scope, SourceValidationRequest request);
}
