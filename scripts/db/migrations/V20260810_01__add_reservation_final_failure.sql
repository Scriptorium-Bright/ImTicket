-- Preserve deterministic reservation failures for idempotent replay.
ALTER TABLE `reservation_idempotency`
    DROP CHECK `chk_reservation_idempotency_success_snapshot`;

ALTER TABLE `reservation_idempotency`
    MODIFY COLUMN `status`
        ENUM('FAILED_FINAL', 'FAILED_RETRYABLE', 'PROCESSING', 'SUCCEEDED') NOT NULL,
    ADD COLUMN `failure_schema_version` INT NULL AFTER `last_error_code`;

ALTER TABLE `reservation_idempotency`
    ADD CONSTRAINT `chk_reservation_idempotency_snapshot`
        CHECK (
            (`status` = 'SUCCEEDED'
                AND `reservation_id` IS NOT NULL
                AND `response_schema_version` IS NOT NULL
                AND `response_payload` IS NOT NULL
                AND `last_error_code` IS NULL
                AND `failure_schema_version` IS NULL)
            OR
            (`status` = 'FAILED_FINAL'
                AND `reservation_id` IS NULL
                AND `response_schema_version` IS NULL
                AND `response_payload` IS NULL
                AND `last_error_code` IS NOT NULL
                AND `failure_schema_version` IS NOT NULL)
            OR
            (`status` IN ('PROCESSING', 'FAILED_RETRYABLE')
                AND `reservation_id` IS NULL
                AND `response_schema_version` IS NULL
                AND `response_payload` IS NULL
                AND `failure_schema_version` IS NULL)
        );

