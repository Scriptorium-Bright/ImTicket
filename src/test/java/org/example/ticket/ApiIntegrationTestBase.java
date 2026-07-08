package org.example.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test") // Loads application-test.yml
public abstract class ApiIntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    // Mock Redis to prevent ConnectionException when Redis is not running
    @MockBean
    protected RedisConnectionFactory redisConnectionFactory;

    @MockBean
    protected org.springframework.data.redis.connection.ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @BeforeEach
    public void setupBase() {
        // Here we could initialize basic test data
    }

}
