package org.example.ticket.config;

import org.example.ticket.venue.stream.SeatCreationConsumer;
import org.example.ticket.venue.stream.SeatCreationProducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new org.springframework.data.redis.serializer.JdkSerializationRedisSerializer());
        template.setHashValueSerializer(
                new org.springframework.data.redis.serializer.JdkSerializationRedisSerializer());
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
            if (!redisTemplate.hasKey(streamKey)) {
                redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
            } else {
                redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
            }
        } catch (Exception ignored) {

        }

        String consumerName = "consumer-" + UUID.randomUUID().toString().substring(0, 8);

        container.start();

        return container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                consumer);
    }
}