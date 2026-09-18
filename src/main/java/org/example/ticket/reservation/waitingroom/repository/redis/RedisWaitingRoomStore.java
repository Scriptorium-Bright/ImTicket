package org.example.ticket.reservation.waitingroom.repository.redis;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomPromotionResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketTransition;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomCapacityException;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomStorageException;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Supplier;

    /** Redis ZSET·Hash와 Lua script로 Waiting Room lifecycle을 원자적으로 처리한다.
     * 모든 회차별 key는 WaitingRoomKeyFactory의 공통 hash tag를 사용한다. */
@Repository
@RequiredArgsConstructor
public class RedisWaitingRoomStore implements WaitingRoomStore {

    private static final RedisScript<String> JOIN_SCRIPT = script("redis/waiting-room/waiting_room_join.lua");
    private static final RedisScript<String> PROMOTE_BATCH_SCRIPT = script(
            "redis/waiting-room/waiting_room_promote_batch.lua"
    );
    private static final RedisScript<String> TRANSITION_SCRIPT = script("redis/waiting-room/waiting_room_transition.lua");

    private final StringRedisTemplate redisTemplate;
    private final WaitingRoomKeyFactory keyFactory;

    /*
     * 실험 브랜치의 promotion 관측용 collaborator다.
     * final field가 아니므로 기존 2-arg constructor와 integration fixture를 깨지 않는다.
     */
    private MeterRegistry meterRegistry;
    private boolean promotionMetadataPipelineEnabled;

    /** Spring runtime에서 promotion phase metric을 기록할 registry를 연결한다. */
    @Autowired(required = false)
    void configureMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 후보 ticket의 memberId를 개별 HGETALL로 읽을지 pipeline HGET으로 읽을지 선택한다.
     * 기본값 false로 기존 경로를 유지해 같은 branch에서 Before/After를 재현할 수 있다.
     */
    @Autowired
    void configurePromotionMetadataPipeline(
            @Value("${reservation.waiting-room.promotion-metadata-pipeline-enabled:false}") boolean enabled
    ) {
        this.promotionMetadataPipelineEnabled = enabled;
    }

    /** integration test에서 A/B 경로를 명시적으로 선택하기 위한 package-private hook이다. */
    void usePipelinedPromotionMetadata(boolean enabled) {
        this.promotionMetadataPipelineEnabled = enabled;
    }

    /** 회원·회차 ticket을 Lua 한 번으로 생성하거나 기존 mapping을 반환한다.
     * sequence, waiting index, deadline index, owner mapping을 함께 기록한다. */
    @Override
    public WaitingRoomJoinResult join(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant enqueuedAt,
            Instant waitingDeadline,
            Duration storageRetention,
            int maxWaitingTickets
    ) {
        requirePositive(performanceTimeId, "performanceTimeId");
        requirePositive(memberId, "memberId");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
        Objects.requireNonNull(waitingDeadline, "waitingDeadline must not be null");
        requirePositiveDuration(storageRetention, "storageRetention");
        requirePositive(maxWaitingTickets, "maxWaitingTickets");
        String result = execute(
                JOIN_SCRIPT,
                List.of(
                        keyFactory.sequence(performanceTimeId),
                        keyFactory.waiting(performanceTimeId),
                        keyFactory.deadline(performanceTimeId),
                        keyFactory.ticket(performanceTimeId, ticketId),
                        keyFactory.owner(performanceTimeId, memberId)
                ),
                ticketId.toString(),
                Long.toString(memberId),
                Long.toString(performanceTimeId),
                Long.toString(enqueuedAt.toEpochMilli()),
                Long.toString(waitingDeadline.toEpochMilli()),
                Long.toString(storageRetention.toMillis()),
                Integer.toString(maxWaitingTickets)
        );
        if ("QUEUE_FULL".equals(result)) {
            throw new WaitingRoomCapacityException();
        }
        String[] parts = splitResult(result, 2);
        return switch (parts[0]) {
            case "CREATED" -> new WaitingRoomJoinResult(true, UUID.fromString(parts[1]), Long.parseLong(parts[2]));
            case "EXISTING" -> new WaitingRoomJoinResult(false, UUID.fromString(parts[1]), -1L);
            default -> throw unexpectedResult(result);
        };
    }

    /** ticket Hash를 문자열 field에서 snapshot으로 복원한다.
     * 손상된 field는 storage 오류로 분류해 호출자에게 전달한다. */
    @Override
    public Optional<WaitingRoomTicketSnapshot> find(long performanceTimeId, UUID ticketId) {
        Map<Object, Object> values;
        try {
            values = redisTemplate.opsForHash().entries(keyFactory.ticket(performanceTimeId, ticketId));
        } catch (RuntimeException exception) {
            throw storageFailure("ticket 조회에 실패했습니다.", exception);
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WaitingRoomTicketSnapshot(
                    ticketId,
                    longValue(values, "memberId"),
                    longValue(values, "performanceTimeId"),
                    WaitingRoomTicketStatus.valueOf(stringValue(values, "status")),
                    longValue(values, "sequence"),
                    instantValue(values, "enqueuedAt"),
                    instantValue(values, "waitingDeadline"),
                    optionalInstantValue(values, "entryExpiresAt")
            ));
        } catch (RuntimeException exception) {
            throw storageFailure("ticket snapshot이 손상됐습니다.", exception);
        }
    }

    /** WAITING ZSET에서 ticket의 현재 zero-based rank를 조회한다.
     * status API가 사용자에게 보여줄 순번의 기반 값이다. */
    @Override
    public OptionalLong waitingRank(long performanceTimeId, UUID ticketId) {
        try {
            Long rank = redisTemplate.opsForZSet().rank(
                    keyFactory.waiting(performanceTimeId),
                    ticketId.toString()
            );
            return rank == null ? OptionalLong.empty() : OptionalLong.of(rank);
        } catch (RuntimeException exception) {
            throw storageFailure("waiting 순번 조회에 실패했습니다.", exception);
        }
    }

    /** 만료 대상 정리와 앞순번 promotion을 반복해 active 상한을 지킨다.
     * 각 후보 전이는 Lua에서 다시 상한과 상태를 확인한다. */
    @Override
    public WaitingRoomPromotionResult promote(
            long performanceTimeId,
            Instant now,
            Duration entryLease,
            int maxActiveSessions,
            int admitPerInterval,
            Duration promotionInterval,
            Duration storageRetention
    ) {
        Objects.requireNonNull(now, "now must not be null");
        requirePositiveDuration(entryLease, "entryLease");
        requirePositive(maxActiveSessions, "maxActiveSessions");
        requirePositive(admitPerInterval, "admitPerInterval");
        requirePositiveDuration(promotionInterval, "promotionInterval");
        requirePositiveDuration(storageRetention, "storageRetention");
        long windowId = Math.floorDiv(now.toEpochMilli(), promotionInterval.toMillis());
        List<WaitingRoomTicketTransition> promoted = new ArrayList<>();
        List<WaitingRoomTicketTransition> expired = timed(
                "expiry_total",
                () -> expireDue(
                        performanceTimeId,
                        now,
                        admitPerInterval,
                        storageRetention
                )
        ).stream()
                .map(snapshot -> new WaitingRoomTicketTransition(snapshot.ticketId(), snapshot.status()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Set<String> candidateSet = timed(
                "candidate_lookup",
                () -> executeCommand(
                        "waiting candidate batch lookup",
                        () -> redisTemplate.opsForZSet().range(
                                keyFactory.waiting(performanceTimeId),
                                0,
                                admitPerInterval - 1L
                        )
                )
        );
        if (candidateSet == null || candidateSet.isEmpty()) {
            return new WaitingRoomPromotionResult(promoted, expired);
        }

        List<UUID> candidateIds = candidateSet.stream()
                .map(this::parseTicketId)
                .toList();
        recordCandidateCount(candidateIds.size());
        List<Long> ownerIds = timed(
                "candidate_metadata",
                () -> resolveCandidateOwnerIds(performanceTimeId, candidateIds)
        );
        List<String> keys = new ArrayList<>(4 + candidateIds.size() * 2);
        keys.add(keyFactory.waiting(performanceTimeId));
        keys.add(keyFactory.active(performanceTimeId));
        keys.add(keyFactory.deadline(performanceTimeId));
        keys.add(keyFactory.admission(performanceTimeId));
        for (int index = 0; index < candidateIds.size(); index++) {
            UUID ticketId = candidateIds.get(index);
            long ownerId = ownerIds.get(index);
            keys.add(keyFactory.ticket(performanceTimeId, ticketId));
            keys.add(keyFactory.owner(performanceTimeId, ownerId));
        }

        List<String> arguments = new ArrayList<>(7 + candidateIds.size());
        arguments.add(Long.toString(now.toEpochMilli()));
        arguments.add(Long.toString(now.plus(entryLease).toEpochMilli()));
        arguments.add(Integer.toString(maxActiveSessions));
        arguments.add(Integer.toString(admitPerInterval));
        arguments.add(Long.toString(windowId));
        arguments.add(Long.toString(storageRetention.toMillis()));
        arguments.add(Integer.toString(candidateIds.size()));
        candidateIds.forEach(ticketId -> arguments.add(ticketId.toString()));

        String result = timed(
                "batch_transition",
                () -> execute(
                        PROMOTE_BATCH_SCRIPT,
                        keys,
                        arguments.toArray(String[]::new)
                )
        );
        appendBatchResult(result, promoted, expired);
        return new WaitingRoomPromotionResult(promoted, expired);
    }

    /**
     * promotion 후보 ticket의 owner ID를 현재 legacy 경로 또는 pipeline 경로로 조회한다.
     * pipeline 경로는 ticket당 HGETALL network round trip을 하나의 pipeline flush로 합친다.
     */
    private List<Long> resolveCandidateOwnerIds(long performanceTimeId, List<UUID> candidateIds) {
        if (!promotionMetadataPipelineEnabled) {
            return candidateIds.stream()
                    .map(ticketId -> find(performanceTimeId, ticketId)
                            .map(WaitingRoomTicketSnapshot::memberId)
                            .orElse(1L))
                    .toList();
        }

        try {
            List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations rawOperations = operations;
                    for (UUID ticketId : candidateIds) {
                        rawOperations.opsForHash().get(
                                keyFactory.ticket(performanceTimeId, ticketId),
                                "memberId"
                        );
                    }
                    return null;
                }
            });
            if (results.size() != candidateIds.size()) {
                throw new WaitingRoomStorageException(
                        "promotion metadata pipeline 결과 개수가 후보 수와 일치하지 않습니다."
                );
            }
            List<Long> ownerIds = new ArrayList<>(results.size());
            for (Object result : results) {
                ownerIds.add(result == null ? 1L : Long.parseLong(result.toString()));
            }
            return ownerIds;
        } catch (WaitingRoomStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw storageFailure("promotion candidate metadata pipeline 조회에 실패했습니다.", exception);
        }
    }

    /** promotion 단계별 시간을 low-cardinality phase/mode tag로 기록한다. */
    private <T> T timed(String phase, Supplier<T> operation) {
        if (meterRegistry == null) {
            return operation.get();
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return operation.get();
        } finally {
            sample.stop(meterRegistry.timer(
                    "imticket.waiting-room.promotion.phase.duration",
                    "phase", phase,
                    "metadata_mode", promotionMetadataPipelineEnabled ? "pipeline" : "legacy"
            ));
        }
    }

    /** 한 promotion cycle에서 읽은 후보 수를 A/B 비교용 distribution으로 기록한다. */
    private void recordCandidateCount(int candidateCount) {
        if (meterRegistry == null) {
            return;
        }
        DistributionSummary.builder("imticket.waiting-room.promotion.candidate.count")
                .tag("metadata_mode", promotionMetadataPipelineEnabled ? "pipeline" : "legacy")
                .register(meterRegistry)
                .record(candidateCount);
    }

    /** batch Lua 결과를 전이 상태별 최소 lifecycle 정보로 복원한다.
     * script는 여러 ticket을 newline으로 반환하며, 알 수 없는 상태는 storage 오류로 처리한다. */
    private void appendBatchResult(
            String result,
            List<WaitingRoomTicketTransition> promoted,
            List<WaitingRoomTicketTransition> expired
    ) {
        if (result == null || result.isBlank()) {
            return;
        }
        for (String transition : result.split("\\n")) {
            String[] parts = splitResult(transition, 2);
            UUID ticketId = parseTicketId(parts[1]);
            switch (parts[0]) {
                case "PROMOTED" -> promoted.add(new WaitingRoomTicketTransition(
                        ticketId,
                        WaitingRoomTicketStatus.ADMITTED
                ));
                case "EXPIRED" -> expired.add(new WaitingRoomTicketTransition(
                        ticketId,
                        WaitingRoomTicketStatus.EXPIRED
                ));
                default -> throw unexpectedResult(result);
            }
        }
    }

    /** Redis ZSET member를 UUID로 변환한다.
     * 손상된 ticket ID는 batch 결과를 부분 성공으로 흘려보내지 않는다. */
    private UUID parseTicketId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw storageFailure("waiting ticket ID가 손상됐습니다.", exception);
        }
    }

    /** owner 검증과 WAITING·ADMITTED ticket 취소를 하나의 Lua transition으로 처리한다.
     * waiting·active·deadline index와 owner mapping을 함께 정리한다. */
    @Override
    public Optional<WaitingRoomTicketSnapshot> cancel(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant now,
            Duration storageRetention
    ) {
        return transition(performanceTimeId, memberId, ticketId, "CANCEL", now, storageRetention);
    }

    /** owner 검증과 ADMITTED ticket 완료를 하나의 Lua transition으로 처리한다.
     * hold 성공 이후 active session slot 반환에 사용한다. */
    @Override
    public Optional<WaitingRoomTicketSnapshot> complete(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            Instant now,
            Duration storageRetention
    ) {
        return transition(performanceTimeId, memberId, ticketId, "COMPLETE", now, storageRetention);
    }

    /** deadline·lease ZSET의 due ticket을 EXPIRED로 전이하고 index를 정리한다.
     * waiting ticket과 admitted lease를 같은 lifecycle 규칙으로 정리한다. */
    private List<WaitingRoomTicketSnapshot> expireDue(
            long performanceTimeId,
            Instant now,
            int batchSize,
            Duration storageRetention
    ) {
        Set<String> waitingDue = timed(
                "expiry_waiting_lookup",
                () -> executeCommand(
                        "waiting deadline lookup",
                        () -> redisTemplate.opsForZSet().rangeByScore(
                                keyFactory.deadline(performanceTimeId), Double.NEGATIVE_INFINITY, now.toEpochMilli(), 0, batchSize
                        )
                )
        );
        Set<String> activeDue = timed(
                "expiry_active_lookup",
                () -> executeCommand(
                        "active lease lookup",
                        () -> redisTemplate.opsForZSet().rangeByScore(
                                keyFactory.active(performanceTimeId), Double.NEGATIVE_INFINITY, now.toEpochMilli(), 0, batchSize
                        )
                )
        );
        List<String> due = new ArrayList<>();
        if (waitingDue != null) {
            due.addAll(waitingDue);
        }
        if (activeDue != null) {
            due.addAll(activeDue);
        }
        List<WaitingRoomTicketSnapshot> expired = new ArrayList<>();
        timed("expiry_transition", () -> {
            for (String ticket : due) {
                UUID ticketId = UUID.fromString(ticket);
                find(performanceTimeId, ticketId)
                        .flatMap(snapshot -> transition(
                                performanceTimeId,
                                snapshot.memberId(),
                                ticketId,
                                "EXPIRE",
                                now,
                                storageRetention
                        ))
                        .ifPresent(expired::add);
            }
            return expired.size();
        });
        return expired;
    }

    /** 지정한 action을 ticket Hash와 모든 lifecycle index에 원자적으로 적용한다.
     * owner 검증과 상태 검증을 Redis script 내부에서 함께 수행한다. */
    private Optional<WaitingRoomTicketSnapshot> transition(
            long performanceTimeId,
            long memberId,
            UUID ticketId,
            String action,
            Instant now,
            Duration storageRetention
    ) {
        String result = execute(
                TRANSITION_SCRIPT,
                List.of(
                        keyFactory.waiting(performanceTimeId),
                        keyFactory.active(performanceTimeId),
                        keyFactory.deadline(performanceTimeId),
                        keyFactory.ticket(performanceTimeId, ticketId),
                        keyFactory.owner(performanceTimeId, memberId <= 0 ? 1 : memberId)
                ),
                ticketId.toString(),
                Long.toString(memberId),
                action,
                Long.toString(now.toEpochMilli()),
                Long.toString(storageRetention.toMillis())
        );
        if (result.equals("MISSING") || result.equals("OWNER_MISMATCH") || result.equals("NOT_DUE")) {
            return Optional.empty();
        }
        if (!result.startsWith("TRANSITIONED|")) {
            throw unexpectedResult(result);
        }
        return find(performanceTimeId, ticketId);
    }

    /** Redis Lua script를 실행하고 storage 예외로 표준화한다.
     * null 결과와 command 예외를 성공 결과로 흘려보내지 않는다. */
    private String execute(RedisScript<String> script, List<String> keys, String... args) {
        try {
            String result = redisTemplate.execute(script, keys, (Object[]) args);
            if (result == null) {
                throw new WaitingRoomStorageException("Redis script가 빈 결과를 반환했습니다.");
            }
            return result;
        } catch (WaitingRoomStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw storageFailure("Redis script 실행에 실패했습니다.", exception);
        }
    }

    /** 일반 Redis command 실패를 Waiting Room storage 오류로 표준화한다.
     * service layer가 raw Redis 예외를 API 응답으로 노출하지 않게 한다. */
    private <T> T executeCommand(String operation, Supplier<T> command) {
        try {
            return command.get();
        } catch (WaitingRoomStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw storageFailure("Redis " + operation + "에 실패했습니다.", exception);
        }
    }

    /** Redis script 결과를 구분자 기준으로 분리하고 최소 field 수를 검증한다.
     * 결과 형식이 바뀌면 즉시 명시적인 storage 오류를 발생시킨다. */
    private String[] splitResult(String result, int minimumParts) {
        String[] parts = result.split("\\|", -1);
        if (parts.length < minimumParts) {
            throw unexpectedResult(result);
        }
        return parts;
    }

    /** 예상하지 못한 Redis script 반환값을 storage 오류로 만든다.
     * 잘못된 결과가 API 성공 응답으로 변환되는 상황을 차단한다. */
    private WaitingRoomStorageException unexpectedResult(String result) {
        return new WaitingRoomStorageException("예상하지 못한 Waiting Room Redis 결과: " + result);
    }

    /** Redis command 예외에 작업 의미를 붙여 storage 오류를 생성한다.
     * 원인 예외는 운영 로그와 장애 분석에서 사용할 수 있게 보존한다. */
    private WaitingRoomStorageException storageFailure(String message, RuntimeException cause) {
        return new WaitingRoomStorageException(message, cause);
    }

    /** Hash field를 문자열로 읽고 누락된 field를 손상된 snapshot으로 처리한다.
     * snapshot 복원 규칙을 한곳에서 유지한다. */
    private String stringValue(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalArgumentException("missing ticket field: " + field);
        }
        return value.toString();
    }

    /** Hash field를 양의 long 값으로 변환한다.
     * field parsing 실패는 상위 snapshot 오류로 전달한다. */
    private long longValue(Map<Object, Object> values, String field) {
        return Long.parseLong(stringValue(values, field));
    }

    /** epoch millisecond Hash field를 Instant로 복원한다.
     * Redis score와 ticket Hash의 시간 표현을 같은 기준으로 읽는다. */
    private Instant instantValue(Map<Object, Object> values, String field) {
        return Instant.ofEpochMilli(longValue(values, field));
    }

    /** 선택적 epoch millisecond field를 nullable Instant로 복원한다.
     * WAITING 상태에는 entry lease field가 없을 수 있다. */
    private Instant optionalInstantValue(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.toString()));
    }

    /** 양수 식별자를 검증한다.
     * 잘못된 입력이 Redis key 생성이나 script 실행까지 도달하지 않게 한다. */
    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Redis key 보존 기간이 양수인지 검증한다.
     * TTL 계산에 zero 또는 음수 duration을 사용하지 않게 한다. */
    private void requirePositiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** classpath의 Lua script를 String 결과 script로 등록한다.
     * RedisTemplate이 실행할 script 결과 타입을 명시한다. */
    private static RedisScript<String> script(String path) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(String.class);
        return script;
    }
}
