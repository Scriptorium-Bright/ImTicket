package org.example.ticket.reservation.shared.intent;

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

    private ReservationIntentFingerprintFactory() {
    }

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

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
