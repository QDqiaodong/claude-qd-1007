package com.amusement.park.dto;

public record CheckItemRequest(
        String itemCode,
        String result,
        String remark,
        String inspectorName) {
}
