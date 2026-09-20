-- 예약·결제 업무 상태와 같은 MySQL 트랜잭션에서 Lifecycle 사건을 보존한다.
ALTER TABLE `Reservation`
    ADD COLUMN `lifecycle_version` BIGINT NOT NULL DEFAULT 0 AFTER `reservation_expired_time`;

CREATE TABLE `lifecycle_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_type` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `schema_version` SMALLINT UNSIGNED NOT NULL,
    `lifecycle_id` BIGINT NOT NULL,
    `payment_order_id` BIGINT NULL,
    `payment_attempt_id` BIGINT NULL,
    `decision_version` BIGINT UNSIGNED NOT NULL,
    `event_ordinal` SMALLINT UNSIGNED NOT NULL,
    `commit_group_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `actor_type` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `occurred_at` DATETIME(6) NOT NULL,
    `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `payload` JSON NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_lifecycle_event_event_id` UNIQUE (`event_id`),
    CONSTRAINT `uk_lifecycle_event_decision_ordinal`
        UNIQUE (`lifecycle_id`, `decision_version`, `event_ordinal`),
    INDEX `idx_lifecycle_event_lifecycle_decision`
        (`lifecycle_id`, `decision_version`, `event_ordinal`),
    INDEX `idx_lifecycle_event_payment_order`
        (`payment_order_id`, `decision_version`),
    INDEX `idx_lifecycle_event_commit_group`
        (`commit_group_id`, `lifecycle_id`),
    CONSTRAINT `fk_lifecycle_event_reservation`
        FOREIGN KEY (`lifecycle_id`) REFERENCES `Reservation` (`id`),
    CONSTRAINT `fk_lifecycle_event_payment_order`
        FOREIGN KEY (`payment_order_id`) REFERENCES `payment_order` (`id`),
    CONSTRAINT `fk_lifecycle_event_payment_attempt`
        FOREIGN KEY (`payment_attempt_id`) REFERENCES `payment_attempt` (`id`)
) ENGINE=InnoDB;
