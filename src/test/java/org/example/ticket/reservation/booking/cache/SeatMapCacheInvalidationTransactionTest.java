package org.example.ticket.reservation.booking.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(SeatMapCacheInvalidationTransactionTest.Config.class)
class SeatMapCacheInvalidationTransactionTest {

    private final SeatMapCacheStore cacheStore;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    SeatMapCacheInvalidationTransactionTest(
            SeatMapCacheStore cacheStore,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager
    ) {
        this.cacheStore = cacheStore;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        clearInvocations(cacheStore);
    }

    @Test
    void invalidatesOnlyAfterTransactionCommits() {
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new SeatMapInvalidationEvent(7L))
        );

        verify(cacheStore).evict(7L);
    }

    @Test
    void doesNotInvalidateWhenTransactionRollsBack() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new SeatMapInvalidationEvent(7L));
            status.setRollbackOnly();
        });

        verify(cacheStore, never()).evict(7L);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(SeatMapCacheInvalidationListener.class)
    static class Config {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SeatMapCacheStore seatMapCacheStore() {
            return mock(SeatMapCacheStore.class);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
