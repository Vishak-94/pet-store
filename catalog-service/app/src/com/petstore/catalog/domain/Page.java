package com.petstore.catalog.domain;

import java.util.Collections;
import java.util.List;

/**
 * A page of catalog results — framework-free value object.
 *
 * <p>Carried over from the legacy {@code catalog.model.Page}, preserving its
 * pagination contract: a sublist plus the start offset and whether next/previous
 * pages exist. {@link #EMPTY_PAGE} is the canonical empty result — the catalog
 * returns it (never null / never a 404) when a lookup finds nothing, matching the
 * legacy behaviour exactly.
 */
public final class Page {

    public static final Page EMPTY_PAGE = new Page(Collections.emptyList(), 0, false);

    private final List<?> objects;
    private final int start;
    private final boolean hasNextPage;

    public Page(List<?> objects, int start, boolean hasNextPage) {
        this.objects = objects == null ? Collections.emptyList() : objects;
        this.start = start;
        this.hasNextPage = hasNextPage;
    }

    public List<?> getList() {
        return objects;
    }

    public boolean isNextPageAvailable() {
        return hasNextPage;
    }

    public boolean isPreviousPageAvailable() {
        return start > 0;
    }

    public int getStartOfNextPage() {
        return start + objects.size();
    }

    public int getStartOfPreviousPage() {
        return Math.max(start - objects.size(), 0);
    }

    public int getSize() {
        return objects.size();
    }
}
