package org.example.ticket.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withThrowUnexpectedException(true)
                        .build()
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "ticket.application.role",
            havingValue = "reservation",
            matchIfMissing = true
    )
    public InitializingBean shedLockTableInitializer(JdbcTemplate jdbcTemplate) {
        return () -> jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS shedlock (
                    name VARCHAR(64) NOT NULL,
                    lock_until TIMESTAMP(3) NOT NULL,
                    locked_at TIMESTAMP(3) NOT NULL,
                    locked_by VARCHAR(255) NOT NULL,
                    PRIMARY KEY (name)
                )
                """);
    }
}
