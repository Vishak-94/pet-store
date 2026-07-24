package com.petstore.warehouse.domain;

/**
 * A line of an incoming order (from the JMS message). Warehouse keeps its own
 * read-model of what to fulfil — it does not query the monolith.
 */
public record OrderLine(String itemId, String productId, String categoryId,
                        int quantity, double unitPrice) {
}
