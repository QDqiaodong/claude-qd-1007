package com.amusement.park.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.amusement.park.entity.Ride;

public interface RideRepository extends JpaRepository<Ride, Long> {

    List<Ride> findByStatus(String status);

    List<Ride> findByRideCode(String rideCode);
}
