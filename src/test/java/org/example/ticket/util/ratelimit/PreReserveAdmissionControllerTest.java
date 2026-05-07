package org.example.ticket.util.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreReserveAdmissionControllerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PreReserveAdmissionController preReserveAdmissionController;

    @BeforeEach
    void setUp() {
        preReserveAdmissionController = new PreReserveAdmissionController(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void acquiresFirstAvailableSlot() {
        when(valueOperations.setIfAbsent(
                eq("adm:pre-reserve:7:slot:0"),
                any(String.class),
                any(java.time.Duration.class)
        )).thenReturn(true);

        PreReserveAdmissionController.AdmissionLease lease = preReserveAdmissionController.acquire(7L);

        assertThat(lease.performanceTimeId()).isEqualTo(7L);
        assertThat(lease.slot()).isEqualTo(0);
        assertThat(lease.key()).isEqualTo("adm:pre-reserve:7:slot:0");
    }

    @Test
    void rejectsWhenAllSlotsAreOccupied() {
        when(valueOperations.setIfAbsent(
                any(String.class),
                any(String.class),
                any(java.time.Duration.class)
        )).thenReturn(false);

        assertThatThrownBy(() -> preReserveAdmissionController.acquire(9L))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void releaseDeletesSlotOnlyWhenTokenMatches() {
        PreReserveAdmissionController.AdmissionLease lease =
                new PreReserveAdmissionController.AdmissionLease("adm:pre-reserve:3:slot:1", "token-1", 3L, 1);
        when(valueOperations.get("adm:pre-reserve:3:slot:1")).thenReturn("token-1");

        preReserveAdmissionController.release(lease);

        verify(stringRedisTemplate).delete("adm:pre-reserve:3:slot:1");
    }
}
