package com.petstore.catalog.service;

import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import com.petstore.catalog.domain.Product;
import com.petstore.catalog.repository.CatalogRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Catalog business logic — replaces the legacy {@code @Stateless CatalogEJB}.
 *
 * <p>Depends only on the {@link CatalogRepository} port (Dependency Inversion);
 * it neither knows nor cares that the adapter is JPA/H2. Behaviour is a
 * pass-through preserving the legacy contract (locale-specific reads, empty
 * results instead of errors), so no business logic changes here.
 */
@Service
public class CatalogService {

    private final CatalogRepository repository;

    public CatalogService(CatalogRepository repository) {
        this.repository = repository;
    }

    public Optional<Category> getCategory(String categoryId, Locale locale) {
        return repository.getCategory(categoryId, locale);
    }

    public Page getCategories(int start, int count, Locale locale) {
        return repository.getCategories(start, count, locale);
    }

    public Optional<Product> getProduct(String productId, Locale locale) {
        return repository.getProduct(productId, locale);
    }

    public Page getProducts(String categoryId, int start, int count, Locale locale) {
        return repository.getProducts(categoryId, start, count, locale);
    }

    public Optional<Item> getItem(String itemId, Locale locale) {
        return repository.getItem(itemId, locale);
    }

    public Page getItems(String productId, int start, int size, Locale locale) {
        return repository.getItems(productId, start, size, locale);
    }

    public Page searchItems(String query, int start, int size, Locale locale) {
        return repository.searchItems(query, start, size, locale);
    }
}
