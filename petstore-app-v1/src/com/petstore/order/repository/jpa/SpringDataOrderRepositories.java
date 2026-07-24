package com.petstore.order.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository backing the order JPA adapter (order creation only). */
interface PurchaseOrderJpaRepository extends JpaRepository<PurchaseOrderEntity, String> {
}
