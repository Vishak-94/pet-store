package com.petstore.order.domain;

/**
 * A line item on a purchase order — framework-free value object.
 * Fields carried over from the legacy {@code lineitem.ejb.LineItem}.
 */
public final class LineItem {

    private final String categoryId;
    private final String productId;
    private final String itemId;
    private final String lineNumber;
    private final int quantity;
    private final double unitPrice;

    public LineItem(String categoryId, String productId, String itemId,
                    String lineNumber, int quantity, double unitPrice) {
        this.categoryId = categoryId;
        this.productId = productId;
        this.itemId = itemId;
        this.lineNumber = lineNumber;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public String getCategoryId() { return categoryId; }
    public String getProductId() { return productId; }
    public String getItemId() { return itemId; }
    public String getLineNumber() { return lineNumber; }
    public int getQuantity() { return quantity; }
    public double getUnitPrice() { return unitPrice; }
}
