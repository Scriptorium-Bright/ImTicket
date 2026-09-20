-- 사건 원본을 운영자가 조회할 수 있는 Lifecycle 상태로 재구성한 파생 모델이다.
CREATE TABLE `lifecycle_event_application` (
    `projection_version` INT NOT NULL,
    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `lifecycle_id` BIGINT NOT NULL,
    `decision_version` BIGINT UNSIGNED NOT NULL,
    `event_ordinal` SMALLINT UNSIGNED NOT NULL,
    `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `error_code` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `applied_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`projection_version`, `event_id`),
    INDEX `idx_lifecycle_event_application_pending`
        (`projection_version`, `status`, `lifecycle_id`, `decision_version`),
    CONSTRAINT `fk_lifecycle_event_application_event`
        FOREIGN KEY (`event_id`) REFERENCES `lifecycle_event` (`event_id`)
) ENGINE=InnoDB;

CREATE TABLE `lifecycle_snapshot` (
    `projection_version` INT NOT NULL,
    `lifecycle_id` BIGINT NOT NULL,
    `payment_order_id` BIGINT NULL,
    `reservation_status` VARCHAR(30) NULL,
    `payment_order_status` VARCHAR(30) NULL,
    `path_classification` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `trust_status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `last_applied_version` BIGINT UNSIGNED NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`projection_version`, `lifecycle_id`),
    INDEX `idx_lifecycle_snapshot_payment_order` (`projection_version`, `payment_order_id`),
    CONSTRAINT `fk_lifecycle_snapshot_reservation`
        FOREIGN KEY (`lifecycle_id`) REFERENCES `Reservation` (`id`),
    CONSTRAINT `fk_lifecycle_snapshot_payment_order`
        FOREIGN KEY (`payment_order_id`) REFERENCES `payment_order` (`id`)
) ENGINE=InnoDB;

CREATE TABLE `lifecycle_seat_snapshot` (
    `projection_version` INT NOT NULL,
    `lifecycle_id` BIGINT NOT NULL,
    `seat_id` BIGINT NOT NULL,
    `seat_status` VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (`projection_version`, `lifecycle_id`, `seat_id`),
    INDEX `idx_lifecycle_seat_snapshot_lifecycle` (`projection_version`, `lifecycle_id`),
    CONSTRAINT `fk_lifecycle_seat_snapshot_lifecycle`
        FOREIGN KEY (`projection_version`, `lifecycle_id`)
        REFERENCES `lifecycle_snapshot` (`projection_version`, `lifecycle_id`),
    CONSTRAINT `fk_lifecycle_seat_snapshot_seat`
        FOREIGN KEY (`seat_id`) REFERENCES `Seat` (`id`)
) ENGINE=InnoDB;

CREATE TABLE `lifecycle_payment_attempt_snapshot` (
    `projection_version` INT NOT NULL,
    `lifecycle_id` BIGINT NOT NULL,
    `payment_attempt_id` BIGINT NOT NULL,
    `payment_attempt_status` VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `provider_transaction_id` VARCHAR(150) NULL,
    `approved_at` DATETIME(6) NULL,
    PRIMARY KEY (`projection_version`, `lifecycle_id`, `payment_attempt_id`),
    INDEX `idx_lifecycle_attempt_snapshot_lifecycle` (`projection_version`, `lifecycle_id`),
    CONSTRAINT `fk_lifecycle_attempt_snapshot_lifecycle`
        FOREIGN KEY (`projection_version`, `lifecycle_id`)
        REFERENCES `lifecycle_snapshot` (`projection_version`, `lifecycle_id`),
    CONSTRAINT `fk_lifecycle_attempt_snapshot_attempt`
        FOREIGN KEY (`payment_attempt_id`) REFERENCES `payment_attempt` (`id`)
) ENGINE=InnoDB;
