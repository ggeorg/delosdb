-- Migrate CARDS Legacy web timeline events into the new CARDS audit table.
--
-- Legacy CARDS stored card timeline rows in p8p_profile.debit_card_eventsource.
-- New CARDS stores both timeline and internal audit rows in p8p_cards.card_event.
-- This migration copies legacy rows that belong to cards already migrated to
-- p8p_cards.cc_card. The generated UID is deterministic, so the migration is
-- idempotent and safe to rerun after Flyway repair/local drift.
--
-- Event time precedence is legacy event date first, associated activity creation time second,
-- and current time only as a last-resort fallback for malformed legacy rows.
-- The migration intentionally does not delete legacy data.

DROP PROCEDURE IF EXISTS `p8p_cards`.`zact_migrate_legacy_card_events`;

CREATE PROCEDURE `p8p_cards`.`zact_migrate_legacy_card_events`()
BEGIN
    DECLARE v_has_legacy_events INT DEFAULT 0;
    DECLARE v_has_legacy_cards INT DEFAULT 0;
    DECLARE v_has_cards_events INT DEFAULT 0;
    DECLARE v_has_cards_cards INT DEFAULT 0;

    SELECT COUNT(*) INTO v_has_legacy_events
    FROM information_schema.tables
    WHERE table_schema = 'p8p_profile'
      AND table_name = 'debit_card_eventsource';

    SELECT COUNT(*) INTO v_has_legacy_cards
    FROM information_schema.tables
    WHERE table_schema = 'p8p_profile'
      AND table_name = 'debit_card';

    SELECT COUNT(*) INTO v_has_cards_events
    FROM information_schema.tables
    WHERE table_schema = 'p8p_cards'
      AND table_name = 'card_event';

    SELECT COUNT(*) INTO v_has_cards_cards
    FROM information_schema.tables
    WHERE table_schema = 'p8p_cards'
      AND table_name = 'cc_card';

    IF v_has_legacy_events = 1
       AND v_has_legacy_cards = 1
       AND v_has_cards_events = 1
       AND v_has_cards_cards = 1 THEN

        INSERT INTO `p8p_cards`.`card_event` (
            `uid`,
            `version`,
            `creation_time`,
            `modification_time`,
            `created_by`,
            `modified_by`,
            `owner`,
            `card_id`,
            `category`,
            `event_type`,
            `cause`,
            `origin_type`,
            `origin_user_uid`,
            `origin_authority`,
            `reason`,
            `message`,
            `metadata_json`,
            `event_version`
        )
        SELECT
            src.`new_uid`,
            0,
            src.`event_time`,
            src.`event_time`,
            'legacy-card-event-migration',
            'legacy-card-event-migration',
            NULL,
            src.`cards_card_id`,
            CASE
                WHEN src.`event_type` = 'CARD_LIMIT_UPDATED' THEN 'CARD_LIMIT'
                WHEN src.`event_type` IN (
                    'CARD_NEW_PAN',
                    'CARD_ISSUED',
                    'CARD_SHIPPED',
                    'CARD_REPLACED',
                    'CARD_ACTIVATED',
                    'CARD_BLOCKED',
                    'CARD_UNBLOCKED',
                    'CARD_CLOSED'
                ) THEN 'CARD_STATE'
                WHEN src.`event_type` IN (
                    'WORKFLOW_INITIALIZED',
                    'ACTIVITY_APPROVED',
                    'ACTIVITY_DENIED'
                ) THEN 'WORKFLOW'
                WHEN src.`event_type` IN (
                    'ACTIVITY_EDITED',
                    'ACTIVITY_CANCELLED',
                    'ACTIVITY_FAILED'
                ) THEN 'ACTIVITY'
                WHEN src.`event_type` IN ('ACTION_CANCELLED', 'ACTION_UPDATED') THEN 'ACTION'
                WHEN src.`event_type` LIKE 'SYSTEM_TASK_%' THEN 'SYSTEM'
                ELSE 'ACTION'
            END AS `category`,
            src.`event_type`,
            src.`cause`,
            src.`origin_type`,
            src.`origin_user_uid`,
            src.`origin_authority`,
            src.`reason`,
            src.`message`,
            src.`metadata_json`,
            src.`event_version`
        FROM (
            SELECT
                CONCAT('legacy-debit-card-eventsource-', e.`id`) AS `new_uid`,
                e.`id` AS `legacy_event_id`,
                c.`id` AS `cards_card_id`,
                COALESCE(
                    e.`date`,
                    act.`creation_time`,
                    CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS UNSIGNED)
                ) AS `event_time`,
                CASE
                    WHEN e.`type` = 'workflow' AND e.`action` = 'workflowInit' THEN 'WORKFLOW_INITIALIZED'
                    WHEN e.`type` = 'limitWorkflow' AND e.`action` = 'workflowInit' THEN 'WORKFLOW_INITIALIZED'
                    WHEN e.`action` = 'workflowApprove' THEN 'ACTIVITY_APPROVED'
                    WHEN e.`action` = 'workflowDeny' THEN 'ACTIVITY_DENIED'
                    WHEN e.`type` = 'activityAction' AND e.`action` = 'edit' THEN 'ACTIVITY_EDITED'
                    WHEN e.`type` = 'activityAction' AND e.`action` = 'cancel' THEN 'ACTIVITY_CANCELLED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'cancel' THEN 'ACTION_CANCELLED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'newPAN' THEN 'CARD_NEW_PAN'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'replace' THEN 'CARD_REPLACED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'limit' THEN 'CARD_LIMIT_UPDATED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'activate' THEN 'CARD_ACTIVATED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'block' THEN 'CARD_BLOCKED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'unblock' THEN 'CARD_UNBLOCKED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'close' THEN 'CARD_CLOSED'
                    WHEN e.`type` = 'cardAction' AND e.`action` = 'shipping' THEN 'CARD_SHIPPED'
                    WHEN e.`type` = 'cardIssued' THEN 'CARD_ISSUED'
                    WHEN e.`type` = 'cardShipped' THEN 'CARD_SHIPPED'
                    WHEN e.`type` = 'cardActivated' THEN 'CARD_ACTIVATED'
                    WHEN e.`type` = 'cardBlocked' THEN 'CARD_BLOCKED'
                    WHEN e.`type` = 'cardUnblocked' THEN 'CARD_UNBLOCKED'
                    WHEN e.`type` = 'cardClosed' THEN 'CARD_CLOSED'
                    WHEN e.`type` = 'cardLimitUpdated' THEN 'CARD_LIMIT_UPDATED'
                    WHEN e.`type` IN ('cardLost', 'cardStolen', 'cardDamaged') THEN 'CARD_REPLACED'
                    WHEN e.`type` = 'cardActionFailed' THEN 'ACTIVITY_FAILED'
                    WHEN e.`type` = 'system' THEN 'SYSTEM_TASK_COMPLETED'
                    WHEN e.`type` = 'other' THEN 'ACTION_UPDATED'
                    ELSE NULL
                END AS `event_type`,
                CASE
                    WHEN e.`cause` = 'lost' THEN 'LOST'
                    WHEN e.`cause` = 'stolen' THEN 'STOLEN'
                    WHEN e.`cause` = 'damaged' THEN 'DAMAGED'
                    WHEN e.`cause` = 'fraud' THEN 'FRAUD'
                    WHEN e.`cause` = 'error' THEN 'ERROR'
                    WHEN e.`cause` = 'other' THEN 'OTHER'
                    WHEN e.`type` = 'cardLost' THEN 'LOST'
                    WHEN e.`type` = 'cardStolen' THEN 'STOLEN'
                    WHEN e.`type` = 'cardDamaged' THEN 'DAMAGED'
                    ELSE NULL
                END AS `cause`,
                CASE
                    WHEN e.`initiator_authority` = 'system' THEN 'SYSTEM'
                    WHEN COALESCE(ui.`uid`, uu.`uid`) IS NULL THEN 'SYSTEM'
                    WHEN e.`initiator_authority` = 'employee' THEN 'CARDHOLDER'
                    ELSE 'MANAGER'
                END AS `origin_type`,
                COALESCE(ui.`uid`, uu.`uid`) AS `origin_user_uid`,
                e.`initiator_authority` AS `origin_authority`,
                LEFT(e.`initiator_reason`, 1024) AS `reason`,
                CONCAT('Migrated legacy card event ', e.`id`) AS `message`,
                CASE
                    WHEN e.`metadata` IS NULL THEN NULL
                    WHEN JSON_VALID(e.`metadata`) THEN e.`metadata`
                    ELSE JSON_OBJECT('legacyMetadata', e.`metadata`)
                END AS `metadata_json`,
                LEFT(COALESCE(e.`event_version`, 'legacy'), 32) AS `event_version`
            FROM `p8p_profile`.`debit_card_eventsource` e
            JOIN `p8p_profile`.`debit_card` dc
              ON dc.`id` = e.`debit_card_id`
            JOIN `p8p_cards`.`cc_card` c
              ON c.`uid` = dc.`uid`
            LEFT JOIN `p8p_profile`.`debit_card_activity` act
              ON act.`id` = e.`card_activity_id`
            LEFT JOIN `p8p_profile`.`employee` emp
              ON emp.`id` = e.`initiator_emp_id`
            LEFT JOIN `p8p_profile`.`user` ui
              ON ui.`id` = emp.`user_id`
            LEFT JOIN `p8p_profile`.`user` uu
              ON uu.`id` = e.`initiator_usr_id`
        ) src
        LEFT JOIN `p8p_cards`.`card_event` existing
          ON existing.`uid` = src.`new_uid`
        WHERE src.`event_type` IS NOT NULL
          AND existing.`id` IS NULL;
    ELSE
        SELECT 'legacy card event migration skipped: required source/target tables are not present';
    END IF;
END;

CALL `p8p_cards`.`zact_migrate_legacy_card_events`();

DROP PROCEDURE IF EXISTS `p8p_cards`.`zact_migrate_legacy_card_events`;
