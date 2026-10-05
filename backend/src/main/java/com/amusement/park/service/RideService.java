package com.amusement.park.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.amusement.park.common.BizException;
import com.amusement.park.common.Dict;
import com.amusement.park.dto.UpdateRideRequest;
import com.amusement.park.entity.Ride;
import com.amusement.park.repository.RideRepository;

/**
 * 游乐设施业务。
 *
 * <p>核心规则：游客乘坐前（开放前校验）必须满足——
 * ① 设施处于「开放」状态；② 身高不低于最低身高限制；③ 年龄不超过上限。
 * 任一不满足则抛出中文 {@link BizException} 拒绝乘坐。</p>
 */
@Service
public class RideService {

    private final RideRepository rideRepository;
    private final SafetyReviewService safetyReviewService;

    public RideService(RideRepository rideRepository, SafetyReviewService safetyReviewService) {
        this.rideRepository = rideRepository;
        this.safetyReviewService = safetyReviewService;
    }

    public List<Ride> list(String status) {
        if (status != null && !status.isBlank()) {
            return rideRepository.findByStatus(status);
        }
        return rideRepository.findAll();
    }

    public Ride get(Long id) {
        return rideRepository.findById(id)
                .orElseThrow(() -> new BizException("设施不存在：" + id));
    }

    @Transactional
    public Ride create(Ride input) {
        if (input.getRideCode() == null || input.getRideCode().isBlank()) {
            throw new BizException("设施编号不能为空");
        }
        if (!rideRepository.findByRideCode(input.getRideCode().trim()).isEmpty()) {
            throw new BizException("设施编号已存在：" + input.getRideCode());
        }
        if (input.getRideName() == null || input.getRideName().isBlank()) {
            throw new BizException("设施名称不能为空");
        }
        if (input.getMinHeightCm() != null && input.getMinHeightCm() < 0) {
            throw new BizException("最低身高不能为负数");
        }
        if (input.getMaxAge() != null && input.getMaxAge() < 0) {
            throw new BizException("最大年龄不能为负数");
        }
        Ride ride = new Ride();
        ride.setRideCode(input.getRideCode().trim());
        ride.setRideName(input.getRideName().trim());
        ride.setMinHeightCm(input.getMinHeightCm());
        ride.setMaxAge(input.getMaxAge());
        ride.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "开放" : input.getStatus());
        if (!Dict.isValidRideStatus(ride.getStatus())) {
            throw new BizException("设施状态不合法：" + ride.getStatus());
        }
        ride.setCreatedAt(LocalDateTime.now());
        Ride saved = rideRepository.save(ride);
        if ("检修".equals(saved.getStatus())) {
            safetyReviewService.startForRide(saved);
        }
        return saved;
    }

    @Transactional
    public Ride update(Long id, UpdateRideRequest input) {
        Ride ride = rideRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("设施不存在：" + id));
        Integer oldMinHeight = ride.getMinHeightCm();
        Integer oldMaxAge = ride.getMaxAge();

        if (input.rideName() != null && !input.rideName().isBlank()) {
            ride.setRideName(input.rideName().trim());
        }
        if (input.minHeightCm() != null) {
            if (input.minHeightCm() < 0) {
                throw new BizException("最低身高不能为负数");
            }
            ride.setMinHeightCm(input.minHeightCm());
        }
        if (input.maxAge() != null) {
            if (input.maxAge() < 0) {
                throw new BizException("最大年龄不能为负数");
            }
            ride.setMaxAge(input.maxAge());
        }

        Ride saved = rideRepository.save(ride);
        if (!Objects.equals(oldMinHeight, saved.getMinHeightCm())
                || !Objects.equals(oldMaxAge, saved.getMaxAge())) {
            safetyReviewService.invalidateForRideChange(saved, "提交后乘坐限制已变化，原安全复核批次失效");
        }
        return saved;
    }

    /** 状态流转；「检修 → 开放」只能由安全复核批次放行完成。 */
    @Transactional
    public Ride transfer(Long id, String target) {
        Ride ride = rideRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("设施不存在：" + id));
        if (!Dict.isValidRideStatus(target)) {
            throw new BizException("设施状态不合法：" + target);
        }
        if ("开放".equals(target) && "检修".equals(ride.getStatus())) {
            throw new BizException("检修后不能直接开放，请完成安全复核并由运营负责人放行");
        }
        if (!Dict.canTransferRide(ride.getStatus(), target)) {
            throw new BizException("不允许从「" + ride.getStatus() + "」转为「" + target + "」");
        }
        String oldStatus = ride.getStatus();
        ride.setStatus(target);
        Ride saved = rideRepository.save(ride);
        if ("检修".equals(target) && !"检修".equals(oldStatus)) {
            safetyReviewService.startForRide(saved);
        }
        if (!"检修".equals(target) && "检修".equals(oldStatus)) {
            safetyReviewService.invalidateForRideLeaveMaintenance(saved);
        }
        return saved;
    }

    /**
     * 乘坐校验（开放前校验）。身高/年龄不满足限制则拒绝。
     *
     * @param heightCm 游客身高（厘米）
     * @param age      游客年龄
     */
    public Ride board(Long id, Integer heightCm, Integer age) {
        Ride ride = rideRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("设施不存在：" + id));
        if (!"开放".equals(ride.getStatus())) {
            throw new BizException("设施「" + ride.getRideName() + "」当前为「" + ride.getStatus() + "」，未开放无法乘坐");
        }
        if (heightCm == null) {
            throw new BizException("请填写游客身高（厘米）");
        }
        if (ride.getMinHeightCm() != null && heightCm < ride.getMinHeightCm()) {
            throw new BizException("身高 " + heightCm + "cm 不足，该设施要求至少 " + ride.getMinHeightCm() + "cm");
        }
        if (age == null) {
            throw new BizException("请填写游客年龄");
        }
        if (ride.getMaxAge() != null && age > ride.getMaxAge()) {
            throw new BizException("年龄 " + age + " 岁超过限制，该设施仅限 " + ride.getMaxAge() + " 岁以下游客");
        }
        return ride;
    }
}
