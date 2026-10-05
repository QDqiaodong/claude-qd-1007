package com.amusement.park.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.amusement.park.common.ApiResponse;
import com.amusement.park.dto.CheckItemRequest;
import com.amusement.park.dto.ReleaseBatchRequest;
import com.amusement.park.dto.SubmitBatchRequest;
import com.amusement.park.entity.RideSafetyBatch;
import com.amusement.park.entity.RideSafetyCheckItem;
import com.amusement.park.service.SafetyReviewService;

/** 检修复核与复开放行接口 */
@RestController
@RequestMapping("/api/safety-batches")
public class SafetyReviewController {

    private final SafetyReviewService safetyReviewService;

    public SafetyReviewController(SafetyReviewService safetyReviewService) {
        this.safetyReviewService = safetyReviewService;
    }

    @GetMapping
    public ApiResponse<List<RideSafetyBatch>> list(@RequestParam(required = false) Long rideId) {
        return ApiResponse.success(safetyReviewService.list(rideId));
    }

    @GetMapping("/{id}")
    public ApiResponse<RideSafetyBatch> get(@PathVariable Long id) {
        return ApiResponse.success(safetyReviewService.get(id));
    }

    @PostMapping
    public ApiResponse<RideSafetyBatch> start(@RequestParam Long rideId) {
        return ApiResponse.success("安全复核批次已生成", safetyReviewService.start(rideId));
    }

    @PostMapping("/{id}/items")
    public ApiResponse<RideSafetyCheckItem> saveItem(@PathVariable Long id,
                                                     @RequestBody CheckItemRequest request) {
        return ApiResponse.success("检查结果已记录", safetyReviewService.saveItem(id, request));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<RideSafetyBatch> submit(@PathVariable Long id,
                                               @RequestBody(required = false) SubmitBatchRequest request) {
        return ApiResponse.success("安全复核已提交，等待运营负责人放行", safetyReviewService.submit(id, request));
    }

    @PostMapping("/{id}/revise")
    public ApiResponse<RideSafetyBatch> revise(@PathVariable Long id) {
        return ApiResponse.success("原批次已失效，新复核批次已生成", safetyReviewService.revise(id));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<RideSafetyBatch> release(@PathVariable Long id,
                                                @RequestBody(required = false) ReleaseBatchRequest request) {
        RideSafetyBatch batch = safetyReviewService.release(id, request);
        return ApiResponse.success(Boolean.TRUE.equals(batch.getRepeated())
                ? "该批次此前已放行，设施仍为开放状态"
                : "双人复核通过，设施已恢复开放", batch);
    }
}
