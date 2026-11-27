package com.livingdocs.document.model;

import java.util.List;

/** Page of the Manager approval queue; totalElements counts only versions inside the actor's scope. */
public record ApprovalQueuePage(List<ApprovalQueueItem> items, int page, int size, long totalElements, int totalPages) {
    public ApprovalQueuePage {
        items = List.copyOf(items);
    }

    public static ApprovalQueuePage of(List<ApprovalQueueItem> items, int page, int size, long total) {
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new ApprovalQueuePage(items, page, size, total, pages);
    }
}
