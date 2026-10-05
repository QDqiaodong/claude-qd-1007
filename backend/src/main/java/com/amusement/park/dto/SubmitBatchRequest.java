package com.amusement.park.dto;

import java.util.List;

public record SubmitBatchRequest(
        String inspectorName,
        List<CheckItemRequest> items) {
}
