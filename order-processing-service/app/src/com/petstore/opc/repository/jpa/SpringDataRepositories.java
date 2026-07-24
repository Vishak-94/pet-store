package com.petstore.opc.repository.jpa;

import com.petstore.opc.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface WarehouseOrderJpaRepository extends JpaRepository<WarehouseOrderEntity, String> {
    List<WarehouseOrderEntity> findByStatus(OrderStatus status);

    /**
     * Revenue (Σ qty·unitPrice) + quantity (Σ qty) grouped by CATEGORY over orders
     * received in [start, end]. Legacy {@code getChartInfo(..., categ == null)}.
     * Rows: {@code [categoryId, revenue(Double), quantity(Long)]}.
     */
    @Query("""
            SELECT l.categoryId, SUM(l.quantity * l.unitPrice), SUM(l.quantity)
            FROM WarehouseOrderEntity o JOIN o.lines l
            WHERE o.created BETWEEN :start AND :end
            GROUP BY l.categoryId""")
    List<Object[]> aggregateByCategory(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Revenue + quantity grouped by ITEM within a single category over orders
     * received in [start, end]. Legacy {@code getChartInfo(..., categ != null)}.
     * Rows: {@code [itemId, revenue(Double), quantity(Long)]}.
     */
    @Query("""
            SELECT l.itemId, SUM(l.quantity * l.unitPrice), SUM(l.quantity)
            FROM WarehouseOrderEntity o JOIN o.lines l
            WHERE o.created BETWEEN :start AND :end AND l.categoryId = :categoryId
            GROUP BY l.itemId""")
    List<Object[]> aggregateByItem(@Param("start") Instant start, @Param("end") Instant end,
                                   @Param("categoryId") String categoryId);
}
