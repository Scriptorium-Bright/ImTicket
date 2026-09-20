-- 조회 모델의 재시도·대조·Replay 근거를 저장한다.
ALTER TABLE `lifecycle_event_application`
    ADD COLUMN `error_type` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE'
        AFTER `error_code`,
    ADD COLUMN `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `error_type`,
    ADD COLUMN `last_attempted_at` DATETIME(6) NULL AFTER `attempt_count`,
    ADD COLUMN `next_attempt_at` DATETIME(6) NULL AFTER `last_attempted_at`;

ALTER TABLE `lifecycle_snapshot`
    ADD COLUMN `reconciled_source_version` BIGINT NULL AFTER `last_applied_version`,
    ADD COLUMN `reconciled_event_version` BIGINT NULL AFTER `reconciled_source_version`,
    ADD COLUMN `reconciliation_diff` JSON NULL AFTER `reconciled_event_version`,
    ADD COLUMN `last_reconciled_at` DATETIME(6) NULL AFTER `reconciliation_diff`;

CREATE TABLE `lifecycle_replay_run` (
    `run_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `projection_version` INT NOT NULL,
    `lifecycle_id` BIGINT NOT NULL,
    `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `requested_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `started_at` DATETIME(6) NULL,
    `completed_at` DATETIME(6) NULL,
    `processed_events` INT UNSIGNED NOT NULL DEFAULT 0,
    `failed_events` INT UNSIGNED NOT NULL DEFAULT 0,
    `error_message` VARCHAR(500) NULL,
    PRIMARY KEY (`run_id`),
    INDEX `idx_lifecycle_replay_run_target`
        (`projection_version`, `lifecycle_id`, `status`, `requested_at`),
    CONSTRAINT `fk_lifecycle_replay_run_reservation`
        FOREIGN KEY (`lifecycle_id`) REFERENCES `Reservation` (`id`)
) ENGINE=InnoDB;
