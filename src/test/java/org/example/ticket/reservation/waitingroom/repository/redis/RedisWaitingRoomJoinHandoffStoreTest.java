package org.example.ticket.reservation.waitingroom.repository.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisWaitingRoomJoinHandoffStoreTest {

    private static final long PERFORMANCE_TIME_ID = 900000001L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private RedisWaitingRoomJoinHandoffStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        store = new RedisWaitingRoomJoinHandoffStore(redisTemplate, new WaitingRoomKeyFactory());
    }

    @Test
    void createsConsumerGroupOncePerStreamWithinApplicationInstance() {
        String stream = new WaitingRoomKeyFactory().joinHandoffStream(PERFORMANCE_TIME_ID);
        when(redisTemplate.hasKey(stream)).thenReturn(true);

        store.ensureConsumerGroup(PERFORMANCE_TIME_ID);
        store.ensureConsumerGroup(PERFORMANCE_TIME_ID);

        verify(streamOperations, times(1)).createGroup(
                eq(stream),
                any(ReadOffset.class),
                eq("waiting-room-join-workers")
        );
    }
}
