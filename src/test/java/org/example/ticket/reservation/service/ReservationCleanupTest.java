package org.example.ticket.reservation.service;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.StorageBasedLockProvider;
import org.example.ticket.ApiIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class ReservationCleanupTest extends ApiIntegrationTestBase {

    private static final String TEST_LOCK_NAME = "cleanupExpiredReservation-test";

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("만료 예약 정리: ShedLock provider는 같은 cleanup lock을 중복 획득하지 않는다")
    public void cleanupExpiredReservationLockIsExclusive() {
        jdbcTemplate.update("delete from shedlock where name = ?", TEST_LOCK_NAME);
        if (lockProvider instanceof StorageBasedLockProvider storageBasedLockProvider) {
            storageBasedLockProvider.clearCache();
        }

        Instant now = Instant.now();
        LockConfiguration configuration = new LockConfiguration(
                now,
                TEST_LOCK_NAME,
                Duration.ofMinutes(6),
                Duration.ZERO
        );

        Optional<SimpleLock> firstLock = lockProvider.lock(configuration);
        Optional<SimpleLock> secondLock = lockProvider.lock(configuration);

        assertThat(firstLock).isPresent();
        assertThat(secondLock).isEmpty();

        firstLock.ifPresent(SimpleLock::unlock);
    }
}
