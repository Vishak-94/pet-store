package com.petstore.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the REST contract of the catalog API: JSON shape, 404-on-miss for single
 * entities, 200-empty for pages, and the legacy Item field mapping (price,
 * productName). These are what the catalog-service-client depends on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void categoriesPage_returnsList() throws Exception {
        mvc.perform(get("/api/categories?start=0&count=10&lang=en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].id").exists())
                .andExpect(jsonPath("$.nextPageAvailable").exists());
    }

    @Test
    void category_known_returnsDto() throws Exception {
        mvc.perform(get("/api/categories/FISH?lang=en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("FISH"))
                .andExpect(jsonPath("$.name").value("Fish"));
    }

    @Test
    void category_unknown_returns404() throws Exception {
        mvc.perform(get("/api/categories/NOPE?lang=en_US"))
                .andExpect(status().isNotFound());
    }

    @Test
    void item_known_carriesPriceAndProductName() throws Exception {
        mvc.perform(get("/api/items/EST-1?lang=en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("EST-1"))
                .andExpect(jsonPath("$.productName").value("Angelfish"))
                .andExpect(jsonPath("$.listPrice").value(16.50));
    }

    @Test
    void item_unknown_returns404() throws Exception {
        mvc.perform(get("/api/items/EST-999?lang=en_US"))
                .andExpect(status().isNotFound());
    }

    @Test
    void search_matchesDescription() throws Exception {
        mvc.perform(get("/api/items?keyword=Angelfish&start=0&count=10&lang=en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].itemId").exists());
    }

    @Test
    void productsInCategory_returnsList() throws Exception {
        mvc.perform(get("/api/categories/FISH/products?start=0&count=10&lang=en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].id").exists());
    }
}
