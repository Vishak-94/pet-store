package com.petstore.catalog.repository;

import com.petstore.catalog.domain.Page;

import java.util.Locale;

/**
 * Keyword-search <b>port</b> for the catalog — deliberately split from
 * {@link CatalogRepository} (Interface Segregation).
 *
 * <p>Browse reads (category → product → item lookups and listings) and free-text
 * search have different shapes and different likely futures: search may later move
 * to a specialised engine (Elasticsearch/OpenSearch) or be carved into its own
 * service, while browse stays a plain locale-split read model. Keeping search on its
 * own narrow port means an alternate search adapter can be swapped in — or lifted out
 * into a separate service — without touching the browse contract or its adapters.
 *
 * <p>Preserves the legacy {@code SEARCH_ITEMS} contract: the query is
 * whitespace-tokenized and, for <b>each</b> token, a case-insensitive
 * {@code LIKE %token%} is ORed across product name + category catid + item descn;
 * tokens are OR-combined; attributes are never searched; a blank / whitespace-only
 * query yields {@link Page#EMPTY_PAGE}. A miss is an empty page, never null / an error.
 */
public interface CatalogSearchPort {

    Page searchItems(String query, int start, int size, Locale locale);
}
