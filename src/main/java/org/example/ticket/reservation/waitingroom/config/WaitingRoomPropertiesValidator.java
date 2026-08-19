package org.example.ticket.reservation.waitingroom.config;

import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.time.Duration;
import java.util.Set;

/** Waiting Room 설정의 필드 간 관계와 활성화 조건을 검증한다. */
public final class WaitingRoomPropertiesValidator implements Validator {

    /** WaitingRoomProperties 설정만 이 Validator가 검증하도록 대상 타입을 제한한다.
     * Spring Validator lifecycle에서 다른 설정 객체에 적용되지 않게 한다. */
    @Override
    public boolean supports(Class<?> clazz) {
        return WaitingRoomProperties.class.isAssignableFrom(clazz);
    }

    /** 단순 필드 제약으로 표현하기 어려운 Waiting Room 설정 관계를 검증한다.
     * 필드 간 순서와 활성화 조건을 설정 binding 단계에서 오류로 전달한다. */
    @Override
    public void validate(Object target, Errors errors) {
        WaitingRoomProperties properties = (WaitingRoomProperties) target;

        validateDurations(properties, errors);
        validatePollingThresholds(properties, errors);
        validateSseExecutor(properties, errors);
        validateLifecyclePublisherExecutor(properties, errors);
        validateJoinHandoff(properties, errors);
        validatePerformanceTimeIds(properties, errors);
        validatePassSecret(properties, errors);
    }

    /** Duration 값이 시간 정책에서 사용할 수 있는 양수인지 검증한다.
     * null 값은 @NotNull 제약이 담당하고, 존재하는 값만 양수인지 확인한다. */
    private void validateDurations(WaitingRoomProperties properties, Errors errors) {
        rejectNonPositive(errors, "waitingTicketTtl", properties.getWaitingTicketTtl());
        rejectNonPositive(errors, "entryLease", properties.getEntryLease());
        rejectNonPositive(errors, "terminalRetention", properties.getTerminalRetention());
        rejectNonPositive(errors, "promotionInterval", properties.getPromotionInterval());
        rejectNonPositive(errors, "statusPollAfter", properties.getStatusPollAfter());
        rejectNonPositive(errors, "statusPollMiddleAfter", properties.getStatusPollMiddleAfter());
        rejectNonPositive(errors, "statusPollFarAfter", properties.getStatusPollFarAfter());
        rejectNonPositive(errors, "sseConnectionTimeout", properties.getSseConnectionTimeout());
        rejectNonPositive(errors, "sseKeepaliveInterval", properties.getSseKeepaliveInterval());
    }

    /** polling 순번 구간이 앞 구간보다 뒤에 오도록 검증한다.
     * far threshold가 middle threshold 이하이면 순번별 polling 정책을 구성할 수 없다. */
    private void validatePollingThresholds(WaitingRoomProperties properties, Errors errors) {
        if (properties.getStatusPollFarThreshold() <= properties.getStatusPollMiddleThreshold()) {
            errors.rejectValue(
                    "statusPollFarThreshold",
                    "waitingRoom.statusPollFarThreshold.order",
                    "statusPollFarThreshold must be greater than statusPollMiddleThreshold"
            );
        }
    }

    /** SSE 전달 executor의 core와 max pool 관계를 설정 binding 단계에서 검증한다.
     * max pool이 core보다 작으면 application startup을 실패시킨다. */
    private void validateSseExecutor(WaitingRoomProperties properties, Errors errors) {
        if (properties.getSseDeliveryMaxPoolSize() < properties.getSseDeliveryCorePoolSize()) {
            errors.rejectValue(
                    "sseDeliveryMaxPoolSize",
                    "waitingRoom.sseDeliveryMaxPoolSize.order",
                    "sseDeliveryMaxPoolSize must be greater than or equal to sseDeliveryCorePoolSize"
            );
        }
    }

    /** lifecycle event publisher의 core와 max pool 관계를 설정 binding 단계에서 검증한다.
     * max pool이 core보다 작으면 event 전달 executor의 동시성 설정이 성립하지 않는다. */
    private void validateLifecyclePublisherExecutor(WaitingRoomProperties properties, Errors errors) {
        if (properties.getLifecyclePublisherMaxPoolSize() < properties.getLifecyclePublisherCorePoolSize()) {
            errors.rejectValue(
                    "lifecyclePublisherMaxPoolSize",
                    "waitingRoom.lifecyclePublisherMaxPoolSize.order",
                    "lifecyclePublisherMaxPoolSize must be greater than or equal to lifecyclePublisherCorePoolSize"
            );
        }
    }

    /** 비동기 join handoff의 worker와 Redis Stream 복구 시간을 검증한다.
     * worker가 처리 시간을 초과하기 전에 pending entry를 중복 claim하지 않도록 관계를 확인한다. */
    private void validateJoinHandoff(WaitingRoomProperties properties, Errors errors) {
        rejectNonPositive(errors, "joinHandoffPollInterval", properties.getJoinHandoffPollInterval());
        rejectNonPositive(errors, "joinHandoffRetryAfter", properties.getJoinHandoffRetryAfter());
        rejectNonPositive(errors, "joinHandoffRecoveryAfter", properties.getJoinHandoffRecoveryAfter());
        if (properties.getJoinHandoffRecoveryAfter() != null
                && properties.getJoinHandoffPollInterval() != null
                && properties.getJoinHandoffRecoveryAfter().compareTo(properties.getJoinHandoffPollInterval()) <= 0) {
            errors.rejectValue(
                    "joinHandoffRecoveryAfter",
                    "waitingRoom.joinHandoffRecoveryAfter.order",
                    "joinHandoffRecoveryAfter must be greater than joinHandoffPollInterval"
            );
        }
    }

    /** 활성화된 Waiting Room의 대상 회차와 회차 ID 값이 유효한지 검증한다.
     * 비활성화 상태에서도 목록에 들어온 회차 ID 자체의 양수 조건은 유지한다. */
    private void validatePerformanceTimeIds(WaitingRoomProperties properties, Errors errors) {
        Set<Long> performanceTimeIds = properties.getEnabledPerformanceTimeIds();
        if (properties.isEnabled() && (performanceTimeIds == null || performanceTimeIds.isEmpty())) {
            errors.rejectValue(
                    "enabledPerformanceTimeIds",
                    "waitingRoom.enabledPerformanceTimeIds.required",
                    "enabledPerformanceTimeIds must contain at least one performance time when Waiting Room is enabled"
            );
        }
        if (performanceTimeIds != null && performanceTimeIds.stream().anyMatch(id -> id == null || id <= 0)) {
            errors.rejectValue(
                    "enabledPerformanceTimeIds",
                    "waitingRoom.enabledPerformanceTimeIds.positive",
                    "enabledPerformanceTimeIds must contain positive values"
            );
        }
    }

    /** 활성화 시 개발용 placeholder secret이 운영 서명 키로 사용되지 않게 검증한다.
     * 실제 운영 secret 주입 여부를 애플리케이션 시작 전에 확인한다. */
    private void validatePassSecret(WaitingRoomProperties properties, Errors errors) {
        if (properties.isEnabled() && WaitingRoomProperties.DEFAULT_PASS_SECRET.equals(properties.getPassSecret())) {
            errors.rejectValue(
                    "passSecret",
                    "waitingRoom.passSecret.placeholder",
                    "Waiting Room 활성화 시 reservation.waiting-room.pass-secret을 변경해야 합니다."
            );
        }
    }

    /** null은 @NotNull 검증에 맡기고, 값이 있으면 양수인지 검증한다.
     * 0과 음수 duration은 시간 정책의 deadline 계산에 사용할 수 없다. */
    private void rejectNonPositive(Errors errors, String fieldName, Duration value) {
        if (value != null && (value.isZero() || value.isNegative())) {
            errors.rejectValue(fieldName, "waitingRoom.duration.positive", fieldName + " must be positive");
        }
    }
}
