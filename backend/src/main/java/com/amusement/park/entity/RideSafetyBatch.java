package com.amusement.park.entity;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 一次设施检修对应的安全复核批次。
 *
 * <p>批次采用追加式记录：参数变化、复核项变化或过期后只作废原批次并另建新批次，
 * 不覆盖任何历史记录。</p>
 */
@Entity
@Table(name = "ride_safety_batch")
@Getter
@Setter
public class RideSafetyBatch {

    public static final String DRAFT = "检修中";
    public static final String SUBMITTED = "待放行";
    public static final String RELEASED = "已放行";
    public static final String INVALID = "已失效";
    public static final String EXPIRED = "已过期";

    /** 复核结果有效时长：小时 */
    public static final long VALID_HOURS = 24;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批次号，唯一 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 48)
    private String batchNo;

    @Column(name = "ride_id", nullable = false)
    private Long rideId;

    /** 检修中 / 待放行 / 已放行 / 已失效 / 已过期 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "inspector_name", length = 32)
    private String inspectorName;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approver_name", length = 32)
    private String approverName;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** 提交瞬间设施状态，应为「检修」 */
    @Column(name = "snapshot_status", length = 16)
    private String snapshotStatus;

    @Column(name = "snapshot_min_height_cm")
    private Integer snapshotMinHeightCm;

    @Column(name = "snapshot_max_age")
    private Integer snapshotMaxAge;

    /** 提交瞬间必检项编码，逗号分隔 */
    @Column(name = "snapshot_check_items", length = 255)
    private String snapshotCheckItems;

    @Column(name = "invalid_reason", length = 255)
    private String invalidReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ---------------- 仅用于接口展示，不入库 ----------------

    @Transient
    private String rideName;

    @Transient
    private String currentRideStatus;

    @Transient
    private Integer currentMinHeightCm;

    @Transient
    private Integer currentMaxAge;

    /** 同一放行人重复请求时为 true，不产生第二次放行 */
    @Transient
    private Boolean repeated = false;

    @Transient
    private List<RideSafetyCheckItem> items;

    @Transient
    public LocalDateTime getValidUntil() {
        return submittedAt == null ? null : submittedAt.plusHours(VALID_HOURS);
    }

    @Transient
    public boolean isContentExpired() {
        return submittedAt != null && submittedAt.plusHours(VALID_HOURS).isBefore(LocalDateTime.now());
    }

    @JsonIgnore
    public boolean isTerminal() {
        return RELEASED.equals(status) || INVALID.equals(status) || EXPIRED.equals(status);
    }
}
