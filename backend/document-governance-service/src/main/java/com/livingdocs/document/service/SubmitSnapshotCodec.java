package com.livingdocs.document.service;
import com.livingdocs.document.model.SubmitResult;
public interface SubmitSnapshotCodec { String encode(SubmitResult result); SubmitResult decode(String snapshot); }
