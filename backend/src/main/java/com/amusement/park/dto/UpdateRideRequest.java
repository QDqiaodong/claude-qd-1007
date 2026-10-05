package com.amusement.park.dto;

public record UpdateRideRequest(
        String rideName,
        Integer minHeightCm,
        Integer maxAge) {
}
