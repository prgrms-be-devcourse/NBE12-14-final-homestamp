ALTER TABLE state.notice
    ADD COLUMN delivery_poll_token uuid,
    ADD COLUMN delivery_poll_started_at timestamptz,
    ADD COLUMN delivery_poll_protected_until timestamptz,
    ADD CONSTRAINT ck_notice_delivery_poll_lease CHECK (
        (delivery_poll_started_at IS NULL AND delivery_poll_protected_until IS NULL)
        OR (delivery_poll_token IS NOT NULL AND delivery_poll_started_at IS NOT NULL
            AND delivery_poll_protected_until > delivery_poll_started_at)
    );
