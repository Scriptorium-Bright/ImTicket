-- Run before deploying the Idempotency-Key requirement for POST /api/reservation/pre-reserve.
CREATE TABLE `reservation_idempotency` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `member_id` BIGINT NOT NULL,
    `idempotency_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `request_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `status` ENUM('FAILED_RETRYABLE', 'PROCESSING', 'SUCCEEDED') NOT NULL,
    `attempt_token` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `lease_expires_at` DATETIME(6) NOT NULL,
    `reservation_id` BIGINT NULL,
    `response_schema_version` INT NULL,
    `response_payload` LONGTEXT NULL,
    `last_error_code` VARCHAR(64) NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_reservation_idempotency_member_key`
        UNIQUE (`member_id`, `idempotency_key`),
    CONSTRAINT `uk_reservation_idempotency_reservation`
        UNIQUE (`reservation_id`),
    INDEX `idx_reservation_idempotency_status_lease` (`status`, `lease_expires_at`),
    CONSTRAINT `fk_reservation_idempotency_member`
        FOREIGN KEY (`member_id`) REFERENCES `Member` (`id`),
    CONSTRAINT `fk_reservation_idempotency_reservation`
        FOREIGN KEY (`reservation_id`) REFERENCES `Reservation` (`id`),
    CONSTRAINT `chk_reservation_idempotency_success_snapshot`
        CHECK (
            (`status` = 'SUCCEEDED'
                AND `reservation_id` IS NOT NULL
                AND `response_schema_version` IS NOT NULL
                AND `response_payload` IS NOT NULL)
            OR
            (`status` <> 'SUCCEEDED'
                AND `reservation_id` IS NULL
                AND `response_schema_version` IS NULL
                AND `response_payload` IS NULL)
        )
) ENGINE=InnoDB;
