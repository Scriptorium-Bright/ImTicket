package org.example.ticket;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.metrics.jfr.FlightRecorderApplicationStartup;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = SecurityAutoConfiguration.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT6M")
@EnableCaching
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(TicketApplication.class);
        if (isStartupJfrEnabled()) {
            application.setApplicationStartup(new FlightRecorderApplicationStartup());
        }
        application.run(args);
    }

    private static boolean isStartupJfrEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                "ticket.startup.jfr.enabled",
                System.getenv().getOrDefault("TICKET_STARTUP_JFR_ENABLED", "false")
        ));
    }
}
