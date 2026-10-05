package com.amusement.park.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 安全复核批次下的逐项检查记录。
 */
@Entity
@Table(name = "ride_safety_check_item")
@Getter
@Setter
public class RideSafetyCheckItem {

    public static final String PENDING = "待检";
    public static final String PASS = "合格";
    public static final String FAIL = "不合格";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** brake / restraint / emergency_stop / no_load_trial */
    @Column(name = "item_code", nullable = false, length = 32)
    private String itemCode;

    /** 制动装置 / 约束装置 / 紧急停机 / 空载试运行 */
    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    /** 待检 / 合格 / 不合格 */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    /** 每项均为必检项 */
    @Column(name = "required", nullable = false)
    private Boolean required = true;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "inspector_name", length = 32)
    private String inspectorName;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;
}
