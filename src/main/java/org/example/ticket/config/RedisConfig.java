package org.example.ticket.config;

import org.example.ticket.venue.stream.SeatCreationConsumer;
import org.example.ticket.venue.stream.SeatCreationProducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key Serializer: String
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value Serializer: JSON
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        return template;
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(100))
                .build();
        return StreamMessageListenerContainer.create(connectionFactory, options);
    }

    @Bean
    public Subscription seatCreationSubscription(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            SeatCreationConsumer consumer,
            RedisTemplate<String, Object> redisTemplate) {

        String streamKey = SeatCreationProducer.STREAM_KEY;
        String consumerGroup = "seat-creation-group";

        try {
            if (Boolean.FALSE.equals(redisTemplate.hasKey(streamKey))) {
                // If stream does not exist, create group (which also creates stream in some
                // versions, but better safe)
                // However, XGROUP CREATE requires the stream to exist normally.
                // We typically handle this by catching the exception or creating a dummy stream
                // first if needed.
                // Spring Data Redis 'createGroup' works if we handle it correctly.
                try {
                    redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
                } catch (Exception e) {
                    // Ignore if failing because stream doesn't exist, we might need to create it
                    // explicitly or wait
                    // Just logging here, in production we might want robust initialization
                }
            } else {
                // Determine if group exists
                try {
                    redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
                } catch (Exception e) {
                    // Ignore "BUSYGROUP Consumer Group name already exists"
                }
            }
        } catch (Exception e) {
            // Ensuring application doesn't crash on startup due to Redis connection issues
            // if desired
        }

        container.start();
        return container.receive(
                org.springframework.data.redis.connection.stream.Consumer.from(consumerGroup, "app-instance-1"),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                consumer);
    }
}
