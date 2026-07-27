package com.petstore.inventory.repository.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data repository over the {@code inventory} table (keyed by {@code itemId}).
 * Beyond the inherited CRUD, it exposes the two operations the oversell-safe reserve
 * needs: a pessimistic-locked read and an atomic bulk increment.
 */
interface InventoryJpaRepository extends JpaRepository<InventoryEntity, String> {

    /** Pessimistic write lock — SELECT ... FOR UPDATE (race-safe reserve). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryEntity i where i.itemId = :itemId")
    Optional<InventoryEntity> findByIdForUpdate(@Param("itemId") String itemId);

    /** Atomic bulk restock — {@code UPDATE … SET quantity = quantity + :qty} (returns rows affected). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update InventoryEntity i set i.quantity = i.quantity + :qty where i.itemId = :itemId")
    int increment(@Param("itemId") String itemId, @Param("qty") int qty);
}
