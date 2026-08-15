package org.example.ticket.reservation.waitingroom.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingRoomPropertiesValidatorTest {

    private final WaitingRoomPropertiesValidator validator = new WaitingRoomPropertiesValidator();

    @Test
    void registersValidatorInConfigurationPropertiesLifecycle() {
        new ApplicationContextRunner()
                .withUserConfiguration(WaitingRoomConfiguration.class)
                .withPropertyValues(
                        "reservation.waiting-room.enabled=true",
                        "reservation.waiting-room.enabled-performance-time-ids=7",
                        "reservation.waiting-room.pass-secret=test-secret"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WaitingRoomPropertiesValidator.class);
                });
    }

    @Test
    void rejectsSimpleConstraintDuringConfigurationPropertiesBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(WaitingRoomConfiguration.class)
                .withPropertyValues("reservation.waiting-room.max-active-sessions=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsCrossFieldConstraintDuringConfigurationPropertiesBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(WaitingRoomConfiguration.class)
                .withPropertyValues(
                        "reservation.waiting-room.status-poll-middle-threshold=100",
                        "reservation.waiting-room.status-poll-far-threshold=100"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsValidEnabledWaitingRoomProperties() {
        WaitingRoomProperties properties = validProperties();

        Errors errors = validate(properties);

        assertThat(errors.hasErrors()).isFalse();
    }

    @Test
    void rejectsPollingThresholdsInWrongOrder() {
        WaitingRoomProperties properties = validProperties();
        properties.setStatusPollFarThreshold(100);

        Errors errors = validate(properties);

        assertThat(errors.hasFieldErrors("statusPollFarThreshold")).isTrue();
    }

    @Test
    void rejectsEnabledWaitingRoomWithoutTargetPerformanceTimes() {
        WaitingRoomProperties properties = validProperties();
        properties.setEnabledPerformanceTimeIds(Set.of());

        Errors errors = validate(properties);

        assertThat(errors.hasFieldErrors("enabledPerformanceTimeIds")).isTrue();
    }

    @Test
    void rejectsPlaceholderPassSecretWhenWaitingRoomIsEnabled() {
        WaitingRoomProperties properties = validProperties();
        properties.setPassSecret(WaitingRoomProperties.DEFAULT_PASS_SECRET);

        Errors errors = validate(properties);

        assertThat(errors.hasFieldErrors("passSecret")).isTrue();
        assertThat(errors.getFieldError("passSecret").getDefaultMessage()).contains("pass-secret");
    }

    @Test
    void rejectsNonPositiveDuration() {
        WaitingRoomProperties properties = validProperties();
        properties.setEntryLease(Duration.ZERO);

        Errors errors = validate(properties);

        assertThat(errors.hasFieldErrors("entryLease")).isTrue();
    }

    @Test
    void rejectsNonPositivePerformanceTimeId() {
        WaitingRoomProperties properties = validProperties();
        properties.setEnabledPerformanceTimeIds(Set.of(0L));

        Errors errors = validate(properties);

        assertThat(errors.hasFieldErrors("enabledPerformanceTimeIds")).isTrue();
    }

    private WaitingRoomProperties validProperties() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(true);
        properties.setPassSecret("test-secret");
        properties.setEnabledPerformanceTimeIds(Set.of(7L));
        return properties;
    }

    private Errors validate(WaitingRoomProperties properties) {
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(properties, "waitingRoomProperties");
        validator.validate(properties, errors);
        return errors;
    }
}
