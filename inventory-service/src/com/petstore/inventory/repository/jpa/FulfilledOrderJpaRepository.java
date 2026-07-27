package com.petstore.inventory.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over the fulfilled-order ledger (keyed by orderId). Plain CRUD
 * is enough: {@code existsById} answers "already shipped?" and {@code save} records a
 * newly-shipped order — the two operations {@link JpaFulfilledOrderStore} needs for
 * idempotent fulfilment (against both JMS redelivery and restock re-drives).
 */
interface FulfilledOrderJpaRepository extends JpaRepository<FulfilledOrderEntity, String> {
}
