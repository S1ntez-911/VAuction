package com.valorcraft.vauction.application;

import java.util.List;

/** A bounded page. One extra row is consumed internally to determine hasNext. */
public record Page<T>(List<T> items, int page, boolean hasPrevious, boolean hasNext) {
    public Page {
        items = List.copyOf(items);
    }
}
