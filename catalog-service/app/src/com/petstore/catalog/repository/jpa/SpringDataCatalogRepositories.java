package com.petstore.catalog.repository.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repositories backing the JPA catalog adapter. Derived + JPQL
 * queries replace the hand-written SQL in the legacy {@code CatalogDAOSQL.xml}
 * while preserving the same lookup semantics (locale-specific, ordered).
 */
interface CategoryDetailRepository extends JpaRepository<CategoryDetailEntity, CategoryDetailEntity.Key> {
    Optional<CategoryDetailEntity> findByCatidAndLocale(String catid, String locale);

    List<CategoryDetailEntity> findByLocaleOrderByCatid(String locale, Pageable pageable);

    long countByLocale(String locale);
}

interface ProductBaseRepository extends JpaRepository<ProductBaseEntity, String> {
    List<ProductBaseEntity> findByCatid(String catid);
}

interface ProductDetailRepository extends JpaRepository<ProductDetailEntity, ProductDetailEntity.Key> {
    Optional<ProductDetailEntity> findByProductidAndLocale(String productid, String locale);

    @Query("select pd from ProductDetailEntity pd, ProductBaseEntity pb "
            + "where pd.productid = pb.productid and pb.catid = :catid and pd.locale = :locale "
            + "order by pd.productid")
    List<ProductDetailEntity> findByCategory(String catid, String locale, Pageable pageable);
}

interface ItemBaseRepository extends JpaRepository<ItemBaseEntity, String> {
    List<ItemBaseEntity> findByProductid(String productid);
}

interface ItemDetailRepository extends JpaRepository<ItemDetailEntity, ItemDetailEntity.Key> {
    Optional<ItemDetailEntity> findByItemidAndLocale(String itemid, String locale);

    @Query("select id from ItemDetailEntity id, ItemBaseEntity ib "
            + "where id.itemid = ib.itemid and ib.productid = :productid and id.locale = :locale "
            + "order by id.itemid")
    List<ItemDetailEntity> findByProduct(String productid, String locale, Pageable pageable);

    @Query("select id from ItemDetailEntity id "
            + "where id.locale = :locale and "
            + "(lower(id.descn) like lower(concat('%', :q, '%')) "
            + " or lower(id.attr1) like lower(concat('%', :q, '%'))) "
            + "order by id.itemid")
    List<ItemDetailEntity> search(String q, String locale, Pageable pageable);
}
