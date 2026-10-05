package com.amusement.park.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.amusement.park.entity.RideSafetyCheckItem;

public interface RideSafetyCheckItemRepository extends JpaRepository<RideSafetyCheckItem, Long> {

    List<RideSafetyCheckItem> findByBatchIdOrderByIdAsc(Long batchId);

    Optional<RideSafetyCheckItem> findByBatchIdAndItemCode(Long batchId, String itemCode);
}
