package com.amusement.park.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.amusement.park.entity.Ride;

import jakarta.persistence.LockModeType;

public interface RideRepository extends JpaRepository<Ride, Long> {

    List<Ride> findByStatus(String status);

    List<Ride> findByRideCode(String rideCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Ride r where r.id = :id")
    Optional<Ride> findByIdForUpdate(@Param("id") Long id);
}
