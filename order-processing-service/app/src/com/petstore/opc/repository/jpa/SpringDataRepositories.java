package com.petstore.opc.repository.jpa;

import com.petstore.opc.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface WarehouseOrderJpaRepository extends JpaRepository<WarehouseOrderEntity, String> {
    List<WarehouseOrderEntity> findByStatus(OrderStatus status);
}
