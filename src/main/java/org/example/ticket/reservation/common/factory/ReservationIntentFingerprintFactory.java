package org.example.ticket.reservation.common.factory;

import org.example.ticket.reservation.common.value.ReservationIntentFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** 예약 요청의 schema, 정렬 규칙과 SHA-256 계산을 한곳에서 적용한다. */
public final class ReservationIntentFingerprintFactory {

    public static final String CURRENT_SCHEMA_VERSION = "reservation-pre-reserve:v1";

    /**
     * 정적 factory만 제공하므로 외부 인스턴스 생성을 막는다.
     * 모든 예약 의도 계산이 동일한 진입점을 사용하게 한다.
     */
    private ReservationIntentFingerprintFactory() {
    }

    /**
     * 회차와 좌석 목록을 canonical 본문으로 만들고 request hash를 계산한다.
     * 정렬된 좌석을 포함한 공통 fingerprint를 Booking과 Queue에 반환한다.
     */
    public static ReservationIntentFingerprint create(long performanceTimeId, List<Long> seatIds) {
        validate(performanceTimeId, seatIds);

        List<Long> normalizedSeatIds = seatIds.stream().sorted().toList();
        String canonical = CURRENT_SCHEMA_VERSION + "\n"
                + "performanceTimeId=" + performanceTimeId + "\n"
                + "seatIds=" + normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return new ReservationIntentFingerprint(
                CURRENT_SCHEMA_VERSION,
                performanceTimeId,
                normalizedSeatIds,
                sha256(canonical)
        );
    }

    /**
     * fingerprint 입력에 양수 회차와 중복 없는 양수 좌석만 허용한다.
     * 잘못된 예약 의도가 hash 계산 단계로 넘어가는 것을 차단한다.
     */
    private static void validate(long performanceTimeId, List<Long> seatIds) {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("seatIds must not be empty");
        }
        if (seatIds.stream().anyMatch(seatId -> seatId == null || seatId <= 0)) {
            throw new IllegalArgumentException("seatIds must contain only positive values");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new IllegalArgumentException("seatIds must not contain duplicates");
        }
    }

    /**
     * canonical 예약 본문을 소문자 SHA-256 문자열로 변환한다.
     * 계산 결과는 동기 예약과 Queue의 요청 내용 비교에 사용한다.
     */
    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
