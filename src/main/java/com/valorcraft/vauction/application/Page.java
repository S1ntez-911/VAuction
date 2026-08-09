package com.valorcraft.vauction.application;

import java.util.List;

/** A bounded page. Totals are exact when supplied by the backing read model. */
public record Page<T>(List<T> items, int page, boolean hasPrevious, boolean hasNext,
                      long totalItems, int totalPages) {
    public Page {
        items = List.copyOf(items);
    }

    public Page(List<T> items, int page, boolean hasPrevious, boolean hasNext) {
        this(items, page, hasPrevious, hasNext, -1, -1);
    }
}
