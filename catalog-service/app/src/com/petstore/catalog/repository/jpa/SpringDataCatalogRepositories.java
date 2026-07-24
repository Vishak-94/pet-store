package com.petstore.catalog.repository.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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

    List<CategoryDetailEntity> findByLocaleOrderByName(String locale, Pageable pageable);

    long countByLocale(String locale);
}

interface ProductBaseRepository extends JpaRepository<ProductBaseEntity, String> {
    List<ProductBaseEntity> findByCatid(String catid);
}

interface ProductDetailRepository extends JpaRepository<ProductDetailEntity, ProductDetailEntity.Key> {
    Optional<ProductDetailEntity> findByProductidAndLocale(String productid, String locale);

    @Query("select pd from ProductDetailEntity pd, ProductBaseEntity pb "
            + "where pd.productid = pb.productid and pb.catid = :catid and pd.locale = :locale "
            + "order by pd.name")
    Slice<ProductDetailEntity> findByCategory(String catid, String locale, Pageable pageable);
}

interface ItemBaseRepository extends JpaRepository<ItemBaseEntity, String> {
    List<ItemBaseEntity> findByProductid(String productid);
}

interface ItemDetailRepository extends JpaRepository<ItemDetailEntity, ItemDetailEntity.Key>, ItemSearchRepository {
    Optional<ItemDetailEntity> findByItemidAndLocale(String itemid, String locale);

    @Query("select id from ItemDetailEntity id, ItemBaseEntity ib "
            + "where id.itemid = ib.itemid and ib.productid = :productid and id.locale = :locale "
            + "order by id.itemid")
    Slice<ItemDetailEntity> findByProduct(String productid, String locale, Pageable pageable);
}

/**
 * Custom fragment for keyword search. The legacy {@code SEARCH_ITEMS} statement
 * tokenizes the query on whitespace and, for EACH token, ORs a case-insensitive
 * {@code LIKE %token%} across product name, category id and item description;
 * tokens are combined with OR. The number of tokens is dynamic, so the clause is
 * assembled at runtime rather than expressed as a static derived/JPQL query.
 */
interface ItemSearchRepository {
    /**
     * @param offset zero-based row offset (legacy scrolled to {@code start + 1})
     * @param limit  max rows to fetch (callers pass {@code count + 1} to detect a next page)
     */
    List<ItemDetailEntity> search(List<String> tokens, String locale, int offset, int limit);
}

class ItemSearchRepositoryImpl implements ItemSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<ItemDetailEntity> search(List<String> tokens, String locale, int offset, int limit) {
        // Legacy SEARCH_ITEMS column set: product name, category catid, item descn
        // (never attributes). Each token ORs the three columns; tokens OR together.
        StringBuilder jpql = new StringBuilder(
                "select id from ItemDetailEntity id, ItemBaseEntity ib, "
                        + "ProductBaseEntity pb, ProductDetailEntity pd "
                        + "where id.itemid = ib.itemid and ib.productid = pb.productid "
                        + "and pb.productid = pd.productid and pd.locale = id.locale "
                        + "and id.locale = :locale and (");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                jpql.append(" or ");
            }
            jpql.append("(lower(pd.name) like :t").append(i)
                    .append(" or lower(pb.catid) like :t").append(i)
                    .append(" or lower(id.descn) like :t").append(i).append(')');
        }
        jpql.append(") order by id.itemid");

        TypedQuery<ItemDetailEntity> query = entityManager.createQuery(jpql.toString(), ItemDetailEntity.class);
        query.setParameter("locale", locale);
        for (int i = 0; i < tokens.size(); i++) {
            query.setParameter("t" + i, "%" + tokens.get(i).toLowerCase() + "%");
        }
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }
}
