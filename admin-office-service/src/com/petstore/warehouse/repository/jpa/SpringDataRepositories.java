package com.petstore.warehouse.repository.jpa;

import com.petstore.warehouse.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface WarehouseOrderJpaRepository extends JpaRepository<WarehouseOrderEntity, String> {
    List<WarehouseOrderEntity> findByStatus(OrderStatus status);
}
