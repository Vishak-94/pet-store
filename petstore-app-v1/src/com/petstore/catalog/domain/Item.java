package com.petstore.catalog.domain;

/**
 * Catalog item (a purchasable variant of a product) — framework-free value object.
 *
 * <p>Carried over from the legacy {@code catalog.model.Item}, preserving all
 * fields and the legacy accessor quirks: {@code getAttribute()} == attribute1,
 * {@code getListCost()} returns listPrice. Plain immutable class (not a record)
 * so view templates can read it via JavaBean getters.
 */
public final class Item {

    private final String category;
    private final String productId;
    private final String productName;
    private final String attribute1;
    private final String attribute2;
    private final String attribute3;
    private final String attribute4;
    private final String attribute5;
    private final String itemId;
    private final String description;
    private final double listPrice;
    private final double unitCost;
    private final String imageLocation;

    public Item(String category, String productId, String productName,
                String attribute1, String attribute2, String attribute3,
                String attribute4, String attribute5, String itemId,
                String description, double listPrice, double unitCost,
                String imageLocation) {
        this.category = category;
        this.productId = productId;
        this.productName = productName;
        this.attribute1 = attribute1;
        this.attribute2 = attribute2;
        this.attribute3 = attribute3;
        this.attribute4 = attribute4;
        this.attribute5 = attribute5;
        this.itemId = itemId;
        this.description = description;
        this.listPrice = listPrice;
        this.unitCost = unitCost;
        this.imageLocation = imageLocation;
    }

    public String getCategory() { return category; }
    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getAttribute1() { return attribute1; }
    public String getAttribute2() { return attribute2; }
    public String getAttribute3() { return attribute3; }
    public String getAttribute4() { return attribute4; }
    public String getAttribute5() { return attribute5; }
    public String getItemId() { return itemId; }
    public String getDescription() { return description; }
    public double getListPrice() { return listPrice; }
    public double getUnitCost() { return unitCost; }
    public String getImageLocation() { return imageLocation; }

    /** Legacy convenience: first attribute (matches legacy getAttribute()). */
    public String getAttribute() { return attribute1; }

    /** Legacy accessor name for list price. */
    public double getListCost() { return listPrice; }
}
