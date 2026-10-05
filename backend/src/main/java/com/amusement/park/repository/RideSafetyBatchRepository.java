package com.amusement.park.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.amusement.park.entity.RideSafetyBatch;

import jakarta.persistence.LockModeType;

public interface RideSafetyBatchRepository extends JpaRepository<RideSafetyBatch, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from RideSafetyBatch b where b.id = :id")
    Optional<RideSafetyBatch> findByIdForUpdate(@Param("id") Long id);

    List<RideSafetyBatch> findAllByOrderByCreatedAtDescIdDesc();

    List<RideSafetyBatch> findByRideIdOrderByCreatedAtDescIdDesc(Long rideId);

    List<RideSafetyBatch> findByRideIdAndStatusInOrderByCreatedAtDescIdDesc(Long rideId, List<String> statuses);

    Optional<RideSafetyBatch> findFirstByRideIdAndStatusOrderByCreatedAtDescIdDesc(Long rideId, String status);
}
