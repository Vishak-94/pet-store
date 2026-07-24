package com.petstore.cart.domain;

/**
 * A line in the shopping cart — framework-free value object.
 *
 * <p>Carried over from the legacy {@code cart.model.CartItem}. Note the legacy
 * quirk preserved verbatim: the "unitCost" field is populated from the catalog
 * item's <em>list price</em> ({@code item.getListCost()}), so {@link #getUnitCost()}
 * returns the list price — this is what subtotal is computed from.
 */
public final class CartItem {

    private final String itemId;
    private final String productId;
    private final String category;
    private final String productName;
    private final String attribute;
    private final int quantity;
    private final double unitCost;

    public CartItem(String itemId, String productId, String category, String productName,
                    String attribute, int quantity, double unitCost) {
        this.itemId = itemId;
        this.productId = productId;
        this.category = category;
        this.productName = productName;
        this.attribute = attribute;
        this.quantity = quantity;
        this.unitCost = unitCost;
    }

    public String getItemId() { return itemId; }
    public String getProductId() { return productId; }
    public String getCategory() { return category; }
    public String getProductName() { return productName; }
    public String getAttribute() { return attribute; }
    public int getQuantity() { return quantity; }
    public double getUnitCost() { return unitCost; }

    public double getTotalCost() { return unitCost * quantity; }
}
