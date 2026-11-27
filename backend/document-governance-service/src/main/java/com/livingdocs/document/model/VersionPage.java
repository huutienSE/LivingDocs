package com.livingdocs.document.model;
import java.util.List;

public record VersionPage(List<VersionDetails> items, int page, int size, long totalElements) {}
