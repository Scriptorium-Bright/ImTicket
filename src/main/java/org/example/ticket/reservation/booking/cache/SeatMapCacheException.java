package org.example.ticket.reservation.booking.cache;

/** Redis 또는 snapshot serialization 실패를 cache 경계 밖으로 전달한다. */
public class SeatMapCacheException extends RuntimeException {

    /**
     * cache storage 또는 serialization 원인을 보존하는 예외를 만든다.
     * 상위 reader가 이 예외를 받아 DB fallback을 선택할 수 있게 한다.
     */
    public SeatMapCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
