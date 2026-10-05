package com.amusement.park.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.amusement.park.common.BizException;
import com.amusement.park.dto.CheckItemRequest;
import com.amusement.park.dto.ReleaseBatchRequest;
import com.amusement.park.dto.SubmitBatchRequest;
import com.amusement.park.entity.Ride;
import com.amusement.park.entity.RideSafetyBatch;
import com.amusement.park.entity.RideSafetyCheckItem;
import com.amusement.park.repository.RideRepository;
import com.amusement.park.repository.RideSafetyBatchRepository;
import com.amusement.park.repository.RideSafetyCheckItemRepository;

/**
 * 检修后安全复核与复开放行闭环。
 */
@Service
public class SafetyReviewService {

    /** 固定必检项，键为稳定编码，值为中文项目名。 */
    public static final Map<String, String> REQUIRED_ITEMS = Map.of(
            "brake", "制动装置",
            "restraint", "约束装置",
            "emergency_stop", "紧急停机",
            "no_load_trial", "空载试运行");

    private static final List<String> ACTIVE_STATUSES =
            List.of(RideSafetyBatch.DRAFT, RideSafetyBatch.SUBMITTED);
    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RideRepository rideRepository;
    private final RideSafetyBatchRepository batchRepository;
    private final RideSafetyCheckItemRepository itemRepository;

    public SafetyReviewService(RideRepository rideRepository,
                               RideSafetyBatchRepository batchRepository,
                               RideSafetyCheckItemRepository itemRepository) {
        this.rideRepository = rideRepository;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
    }

    // ---------------- 查询 ----------------

    @Transactional
    public List<RideSafetyBatch> list(Long rideId) {
        List<RideSafetyBatch> batches = rideId == null
                ? batchRepository.findAllByOrderByCreatedAtDescIdDesc()
                : batchRepository.findByRideIdOrderByCreatedAtDescIdDesc(rideId);
        markExpired(batches);
        return enrich(batches);
    }

    @Transactional
    public RideSafetyBatch get(Long id) {
        RideSafetyBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new BizException("安全复核批次不存在：" + id));
        markExpiredIfNeeded(batch);
        return enrich(batch);
    }

    // ---------------- 批次生命周期 ----------------

    /** 设施进入检修时由状态流转内部调用；调用方应先持有设施行锁。 */
    @Transactional
    public RideSafetyBatch startForRide(Ride ride) {
        return batchRepository.findFirstByRideIdAndStatusOrderByCreatedAtDescIdDesc(ride.getId(), RideSafetyBatch.DRAFT)
                .map(this::enrich)
                .orElseGet(() -> enrich(createBatch(ride)));
    }

    /** 人工为检修设施开启一个新批次。 */
    @Transactional
    public RideSafetyBatch start(Long rideId) {
        Ride ride = getRideForUpdate(rideId);
        if (!"检修".equals(ride.getStatus())) {
            throw new BizException("设施当前为「" + ride.getStatus() + "」，只有进入检修后才能生成安全复核批次");
        }
        return startForRide(ride);
    }

    @Transactional
    public RideSafetyCheckItem saveItem(Long batchId, CheckItemRequest request) {
        RideSafetyBatch preload = batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("安全复核批次不存在：" + batchId));
        Ride ride = getRideForUpdate(preload.getRideId());
        RideSafetyBatch batch = getBatchForUpdate(batchId);
        requireDraft(batch);
        if (!"检修".equals(ride.getStatus())) {
            invalidate(batch, "设施已离开检修状态，原批次禁止放行");
            throw new BizException("设施当前为「" + ride.getStatus() + "」，该复核批次已失效");
        }

        String code = requiredText(request == null ? null : request.itemCode(), "检查项编码");
        String name = REQUIRED_ITEMS.get(code);
        if (name == null) {
            throw new BizException("不支持的复核项：" + code);
        }
        String result = requiredText(request.result(), "检查结果");
        if (!Set.of(RideSafetyCheckItem.PASS, RideSafetyCheckItem.FAIL).contains(result)) {
            throw new BizException("检查结果只能为「合格」或「不合格」");
        }
        String inspector = requiredText(request.inspectorName(), "安全员姓名");

        RideSafetyCheckItem item = itemRepository.findByBatchIdAndItemCode(batchId, code)
                .orElseThrow(() -> new BizException("复核项缺失：" + name));
        item.setResult(result);
        item.setRemark(request.remark());
        item.setInspectorName(inspector);
        item.setCheckedAt(LocalDateTime.now());
        return itemRepository.save(item);
    }

    @Transactional
    public RideSafetyBatch submit(Long batchId, SubmitBatchRequest request) {
        RideSafetyBatch preload = batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("安全复核批次不存在：" + batchId));
        Ride ride = getRideForUpdate(preload.getRideId());
        RideSafetyBatch batch = getBatchForUpdate(batchId);
        requireDraft(batch);
        if (!"检修".equals(ride.getStatus())) {
            invalidate(batch, "设施已离开检修状态，原批次禁止放行");
            throw new BizException("设施当前为「" + ride.getStatus() + "」，该复核批次已失效");
        }

        String inspector = requiredText(request == null ? null : request.inspectorName(), "安全员姓名");
        List<RideSafetyCheckItem> items = itemRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<String, RideSafetyCheckItem> itemMap = items.stream()
                .collect(Collectors.toMap(RideSafetyCheckItem::getItemCode, Function.identity()));

        if (request != null && request.items() != null && !request.items().isEmpty()) {
            for (CheckItemRequest itemRequest : request.items()) {
                String code = requiredText(itemRequest.itemCode(), "检查项编码");
                RideSafetyCheckItem item = itemMap.get(code);
                if (item == null) {
                    throw new BizException("未知复核项：" + code);
                }
                String result = requiredText(itemRequest.result(), "检查结果");
                if (!Set.of(RideSafetyCheckItem.PASS, RideSafetyCheckItem.FAIL).contains(result)) {
                    throw new BizException("检查结果只能为「合格」或「不合格」");
                }
                item.setResult(result);
                item.setRemark(itemRequest.remark());
                item.setInspectorName(inspector);
                item.setCheckedAt(LocalDateTime.now());
            }
        }

        items = itemRepository.findByBatchIdOrderByIdAsc(batchId);
        if (items.size() != REQUIRED_ITEMS.size()) {
            throw new BizException("必检项不完整，不能提交安全复核");
        }
        for (String code : REQUIRED_ITEMS.keySet()) {
            RideSafetyCheckItem item = itemMap.get(code);
            if (item == null || !RideSafetyCheckItem.PASS.equals(item.getResult())) {
                throw new BizException("必检项「" + REQUIRED_ITEMS.get(code) + "」尚未合格，不能提交");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        batch.setInspectorName(inspector);
        batch.setSubmittedAt(now);
        batch.setStatus(RideSafetyBatch.SUBMITTED);
        batch.setSnapshotStatus(ride.getStatus());
        batch.setSnapshotMinHeightCm(ride.getMinHeightCm());
        batch.setSnapshotMaxAge(ride.getMaxAge());
        batch.setSnapshotCheckItems(REQUIRED_ITEMS.keySet().stream().sorted().collect(Collectors.joining(",")));
        batch.setInvalidReason(null);
        return enrich(batchRepository.save(batch));
    }

    /**
     * 负责人放行。先锁设施再锁批次，保证两人同时点击或刷新重试只成功一次。
     * 业务异常也提交已产生的“过期/失效”状态，避免历史状态被回滚。
     */
    @Transactional(noRollbackFor = BizException.class)
    public RideSafetyBatch release(Long batchId, ReleaseBatchRequest request) {
        RideSafetyBatch preload = batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("安全复核批次不存在：" + batchId));
        Ride ride = getRideForUpdate(preload.getRideId());
        RideSafetyBatch batch = getBatchForUpdate(batchId);

        String approver = requiredText(request == null ? null : request.approverName(), "运营负责人姓名");

        if (RideSafetyBatch.RELEASED.equals(batch.getStatus())) {
            if (approver.equals(batch.getApproverName())) {
                batch.setRepeated(true);
                return enrich(batch);
            }
            throw new BizException("该批次已由「" + batch.getApproverName() + "」放行，设施当前为「"
                    + ride.getStatus() + "」，本次操作未改变开放状态");
        }
        if (RideSafetyBatch.INVALID.equals(batch.getStatus())) {
            throw new BizException("批次已失效：" + batch.getInvalidReason()
                    + "；设施当前为「" + ride.getStatus() + "」");
        }
        if (RideSafetyBatch.EXPIRED.equals(batch.getStatus()) || isExpired(batch)) {
            if (!RideSafetyBatch.EXPIRED.equals(batch.getStatus())) {
                batch.setStatus(RideSafetyBatch.EXPIRED);
                batch.setInvalidReason("复核结果距提交已超过 " + RideSafetyBatch.VALID_HOURS + " 小时");
                batchRepository.save(batch);
            }
            throw new BizException("复核结果已超过 " + RideSafetyBatch.VALID_HOURS
                    + " 小时有效期，设施当前为「" + ride.getStatus() + "」，请重新生成批次");
        }
        if (!RideSafetyBatch.SUBMITTED.equals(batch.getStatus())) {
            throw new BizException("批次当前为「" + batch.getStatus() + "」，尚未完成安全员提交，设施当前为「"
                    + ride.getStatus() + "」");
        }
        if (!"检修".equals(ride.getStatus())) {
            invalidate(batch, "设施已离开检修状态，原批次禁止放行");
            throw new BizException("设施当前为「" + ride.getStatus() + "」，该批次已失效，未改变开放状态");
        }
        if (approver.equals(batch.getInspectorName())) {
            throw new BizException("提交人「" + batch.getInspectorName()
                    + "」不能担任最终放行人，请由另一名运营负责人确认");
        }

        String changed = snapshotChanged(batch, ride);
        if (changed != null) {
            invalidate(batch, changed);
            throw new BizException(changed + "，原批次已失效；设施仍为「" + ride.getStatus()
                    + "」，请重新复核后再放行");
        }

        LocalDateTime now = LocalDateTime.now();
        batch.setApproverName(approver);
        batch.setReleasedAt(now);
        batch.setStatus(RideSafetyBatch.RELEASED);
        ride.setStatus("开放");
        rideRepository.save(ride);
        RideSafetyBatch released = batchRepository.save(batch);
        invalidateOtherActiveBatches(ride.getId(), released.getId(),
                "设施已由批次 " + released.getBatchNo() + " 放行，其他未闭环批次作废");
        return enrich(released);
    }

    /** 对已提交但尚未放行的批次发起重检：旧批次失效留痕，新批次另立记录。 */
    @Transactional
    public RideSafetyBatch revise(Long batchId) {
        RideSafetyBatch preload = batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("安全复核批次不存在：" + batchId));
        Ride ride = getRideForUpdate(preload.getRideId());
        RideSafetyBatch old = getBatchForUpdate(batchId);
        if (!"检修".equals(ride.getStatus())) {
            throw new BizException("设施当前为「" + ride.getStatus() + "」，不能创建检修复核批次");
        }
        if (!RideSafetyBatch.SUBMITTED.equals(old.getStatus())) {
            throw new BizException("仅已提交待放行的批次可以重检；当前批次为「" + old.getStatus() + "」");
        }
        RideSafetyBatch fresh = createBatch(ride);
        old.setStatus(RideSafetyBatch.INVALID);
        old.setInvalidReason("安全员发起重检，已由新批次 " + fresh.getBatchNo() + " 替代");
        batchRepository.save(old);
        return enrich(fresh);
    }

    // ---------------- 设施变更联动 ----------------

    /** 已提交批次的身高、年龄限制变化后立即失效；草稿仍可继续编辑。 */
    @Transactional
    public void invalidateForRideChange(Ride ride, String reason) {
        List<RideSafetyBatch> batches = batchRepository
                .findByRideIdAndStatusInOrderByCreatedAtDescIdDesc(ride.getId(),
                        List.of(RideSafetyBatch.SUBMITTED));
        for (RideSafetyBatch batch : batches) {
            invalidate(batch, reason);
        }
    }

    /** 设施离开检修时，本次未闭环的草稿和待放行批次都不得被后续检修周期复用。 */
    @Transactional
    public void invalidateForRideLeaveMaintenance(Ride ride) {
        invalidateOtherActiveBatches(ride.getId(), null, "设施离开检修状态，原安全复核批次失效");
    }

    // ---------------- 内部方法 ----------------

    private void invalidateOtherActiveBatches(Long rideId, Long keepBatchId, String reason) {
        List<RideSafetyBatch> batches = batchRepository
                .findByRideIdAndStatusInOrderByCreatedAtDescIdDesc(rideId, ACTIVE_STATUSES);
        for (RideSafetyBatch batch : batches) {
            if (!batch.getId().equals(keepBatchId)) {
                invalidate(batch, reason);
            }
        }
    }

    private RideSafetyBatch createBatch(Ride ride) {
        LocalDateTime now = LocalDateTime.now();
        RideSafetyBatch batch = new RideSafetyBatch();
        batch.setBatchNo(generateBatchNo(now));
        batch.setRideId(ride.getId());
        batch.setStatus(RideSafetyBatch.DRAFT);
        batch.setCreatedAt(now);
        batch = batchRepository.save(batch);

        for (Map.Entry<String, String> entry : REQUIRED_ITEMS.entrySet()) {
            RideSafetyCheckItem item = new RideSafetyCheckItem();
            item.setBatchId(batch.getId());
            item.setItemCode(entry.getKey());
            item.setItemName(entry.getValue());
            item.setResult(RideSafetyCheckItem.PENDING);
            item.setRequired(true);
            itemRepository.save(item);
        }
        return batch;
    }

    private String generateBatchNo(LocalDateTime time) {
        return "AQ-" + time.format(BATCH_TIME) + "-"
                + String.format("%08d", Math.floorMod(time.getNano() * 1_000_003L
                        + ThreadLocalRandom.current().nextInt(10_000_000), 100_000_000L));
    }

    private Ride getRideForUpdate(Long rideId) {
        return rideRepository.findByIdForUpdate(rideId)
                .orElseThrow(() -> new BizException("设施不存在：" + rideId));
    }

    private RideSafetyBatch getBatchForUpdate(Long batchId) {
        return batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new BizException("安全复核批次不存在：" + batchId));
    }

    private void requireDraft(RideSafetyBatch batch) {
        if (!RideSafetyBatch.DRAFT.equals(batch.getStatus())) {
            throw new BizException("批次当前为「" + batch.getStatus() + "」，检查记录已锁定，不能修改");
        }
    }

    private boolean isExpired(RideSafetyBatch batch) {
        return RideSafetyBatch.SUBMITTED.equals(batch.getStatus())
                && batch.getSubmittedAt() != null
                && batch.getSubmittedAt().plusHours(RideSafetyBatch.VALID_HOURS).isBefore(LocalDateTime.now());
    }

    private void markExpired(List<RideSafetyBatch> batches) {
        for (RideSafetyBatch batch : batches) {
            markExpiredIfNeeded(batch);
        }
    }

    private void markExpiredIfNeeded(RideSafetyBatch batch) {
        if (isExpired(batch)) {
            batch.setStatus(RideSafetyBatch.EXPIRED);
            batch.setInvalidReason("复核结果距提交已超过 " + RideSafetyBatch.VALID_HOURS + " 小时");
            batchRepository.save(batch);
        }
    }

    private String snapshotChanged(RideSafetyBatch batch, Ride ride) {
        if (!Objects.equals(batch.getSnapshotStatus(), ride.getStatus())) {
            return "提交后设施状态已变化";
        }
        if (!Objects.equals(batch.getSnapshotMinHeightCm(), ride.getMinHeightCm())) {
            return "提交后最低身高限制已变化";
        }
        if (!Objects.equals(batch.getSnapshotMaxAge(), ride.getMaxAge())) {
            return "提交后最高年龄限制已变化";
        }
        String requiredCodes = REQUIRED_ITEMS.keySet().stream().sorted().collect(Collectors.joining(","));
        String snapshotCodes = batch.getSnapshotCheckItems() == null
                ? ""
                : sortedCsv(batch.getSnapshotCheckItems());
        if (!requiredCodes.equals(snapshotCodes)) {
            return "提交后必检复核项已变化";
        }
        return null;
    }

    private String sortedCsv(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .filter(s -> !s.isBlank())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private void invalidate(RideSafetyBatch batch, String reason) {
        batch.setStatus(RideSafetyBatch.INVALID);
        batch.setInvalidReason(reason);
        batchRepository.save(batch);
    }

    private List<RideSafetyBatch> enrich(List<RideSafetyBatch> batches) {
        batches.forEach(this::enrich);
        return batches;
    }

    private RideSafetyBatch enrich(RideSafetyBatch batch) {
        Ride ride = rideRepository.findById(batch.getRideId()).orElse(null);
        if (ride != null) {
            batch.setRideName(ride.getRideName());
            batch.setCurrentRideStatus(ride.getStatus());
            batch.setCurrentMinHeightCm(ride.getMinHeightCm());
            batch.setCurrentMaxAge(ride.getMaxAge());
        }
        batch.setItems(itemRepository.findByBatchIdOrderByIdAsc(batch.getId()));
        return batch;
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(field + "不能为空");
        }
        return value.trim();
    }
}
