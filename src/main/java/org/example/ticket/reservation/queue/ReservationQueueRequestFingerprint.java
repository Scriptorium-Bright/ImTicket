package org.example.ticket.reservation.queue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Queue 요청의 동일성을 비교할 정렬 좌석 목록과 SHA-256 fingerprint다. */
public final class ReservationQueueRequestFingerprint {

    private static final String SCHEMA = "reservation-pre-reserve:v1";

    private final long performanceTimeId;
    private final List<Long> normalizedSeatIds;
    private final String requestHash;

    private ReservationQueueRequestFingerprint(
            long performanceTimeId,
            List<Long> normalizedSeatIds,
            String requestHash
    ) {
        this.performanceTimeId = performanceTimeId;
        this.normalizedSeatIds = List.copyOf(normalizedSeatIds);
        this.requestHash = requestHash;
    }

    public static ReservationQueueRequestFingerprint of(long performanceTimeId, List<Long> seatIds) {
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

        List<Long> normalizedSeatIds = seatIds.stream().sorted().toList();
        String canonical = SCHEMA + "\n"
                + "performanceTimeId=" + performanceTimeId + "\n"
                + "seatIds=" + normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return new ReservationQueueRequestFingerprint(
                performanceTimeId,
                normalizedSeatIds,
                sha256(canonical)
        );
    }

    public long performanceTimeId() {
        return performanceTimeId;
    }

    public List<Long> normalizedSeatIds() {
        return normalizedSeatIds;
    }

    public String requestHash() {
        return requestHash;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
