package com.petstore.customer.domain;

/**
 * Customer profile preferences — framework-free value object.
 *
 * <p>Carried over from the legacy {@code customer.profile} component
 * (preferredLanguage, favoriteCategory, myListPreference, bannerPreference).
 * Legacy defaults: language/category null, both boolean prefs {@code false}.
 */
public final class Profile {

    private final String preferredLanguage;
    private final String favoriteCategory;
    private final boolean myListPreference;
    private final boolean bannerPreference;

    public Profile(String preferredLanguage, String favoriteCategory,
                   boolean myListPreference, boolean bannerPreference) {
        this.preferredLanguage = preferredLanguage;
        this.favoriteCategory = favoriteCategory;
        this.myListPreference = myListPreference;
        this.bannerPreference = bannerPreference;
    }

    /** Legacy default profile. */
    public static Profile defaults() {
        return new Profile(null, null, false, false);
    }

    public String getPreferredLanguage() { return preferredLanguage; }
    public String getFavoriteCategory() { return favoriteCategory; }
    public boolean isMyListPreference() { return myListPreference; }
    public boolean isBannerPreference() { return bannerPreference; }
}
