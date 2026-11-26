-- HomeStamp DB grants: one DO block executed over JDBC (no psql meta-commands).
--
-- Executor contract (see DatabaseGrantsIntegrationTest.GrantsJdbcExecutor):
--   1. connect as the migration login (LOGIN NOINHERIT, owner membership SET TRUE / INHERIT FALSE / ADMIN FALSE)
--   2. autocommit off, then SET LOCAL ROLE "<owner>" with a validated identifier
--   3. SELECT set_config('homestamp.grants.{app,purge,human}_role', ?, true) via PreparedStatement
--   4. SELECT pg_advisory_xact_lock(1752392040, 156)
--   5. execute this block, then COMMIT (ROLLBACK on any error)
-- The owner role is current_user after step 2. This file never creates credentials or changes ownership.
-- public schema ACLs must be prepared by the bootstrap (database administrator); this block only checks them.
-- It lives outside db/migration so Flyway never runs it.
DO $$
DECLARE
    owner_role name := current_user;
    app_role name := nullif(current_setting('homestamp.grants.app_role', true), '')::name;
    purge_role name := nullif(current_setting('homestamp.grants.purge_role', true), '')::name;
    human_role name := nullif(current_setting('homestamp.grants.human_role', true), '')::name;
    expected_tables text[] := ARRAY[
        'log.deletion_log',
        'record.action',
        'record.cause_note',
        'record.inspection_item_version',
        'record.member_acknowledgement',
        'record.payment_version',
        'record.photo',
        'record.proposal',
        'record.proposal_line',
        'record.progress_note',
        'record.repair_content',
        'record.settlement_transfer_version',
        'record.supplement',
        'state.action_chain_head',
        'state.correction_request',
        'state.in_app_notification',
        'state.inspection',
        'state.inspection_item',
        'state.link',
        'state.member',
        'state.notice',
        'state.outbox',
        'state.party',
        'state.part_template',
        'state.payment',
        'state.phone_verification',
        'state.photo_visibility',
        'state.progress_note_response',
        'state.rate_counter',
        'state.region',
        'state.repair_case',
        'state.refresh_token',
        'state.response_request',
        'state.room_part',
        'state.room_record',
        'state.settlement',
        'state.settlement_item',
        'state.settlement_transfer',
        'state.sms_opt_out'
    ];
    actual_tables text[];
    public_tables text[];
    schema_name name;
    expected_functions text[] := ARRAY[
        'record.forbid_change()',
        'record.purge_member_acknowledgements(uuid, timestamp with time zone)',
        'state.forbid_link_revalidate()',
        'state.guard_room_delete()',
        'state.purge_room_record(uuid)'
    ];
    actual_functions text[];
    -- Human (per-person read-only login): explicit allowlist. Every table in expected_tables must be classified
    -- in exactly one of these lists; a new table without a decision fails closed. No default privileges for Human.
    human_full_tables text[] := ARRAY[
        'log.deletion_log',
        'record.action',
        'record.inspection_item_version',
        'record.member_acknowledgement',
        'record.payment_version',
        'record.photo',
        'record.proposal',
        'record.proposal_line',
        'record.progress_note',
        'record.repair_content',
        'record.settlement_transfer_version',
        'record.supplement',
        'state.action_chain_head',
        'state.correction_request',
        'state.in_app_notification',
        'state.inspection',
        'state.inspection_item',
        'state.notice',
        'state.payment',
        'state.photo_visibility',
        'state.progress_note_response',
        'state.rate_counter',
        'state.region',
        'state.repair_case',
        'state.response_request',
        'state.room_part',
        'state.room_record',
        'state.settlement',
        'state.settlement_item',
        'state.settlement_transfer',
        'state.part_template',
        'state.sms_opt_out'
    ];
    -- Column-level SELECT only. Excluded: credentials and hashes used for authentication (password_hash,
    -- token_hash, code_hash), plaintext phone numbers and email, display_name, device_label, outbox payload/dedupe_key,
    -- and cause_note.expert_name (a real third-party person's name).
    human_column_tables text[] := ARRAY[
        'record.cause_note',
        'state.member',
        'state.refresh_token',
        'state.phone_verification',
        'state.link',
        'state.party',
        'state.outbox'
    ];
    human_column_lists text[] := ARRAY[
        'id,room_record_id,repair_case_id,side,text,basis,previous_id,created_at',
        'id,phone_verified_at,sms_enabled,status,withdrawn_at,created_at,version',
        'id,member_id,family_id,expires_at,revoked_at,replaced_by_id,created_at,version',
        'id,purpose,expires_at,attempt_count,verified_at,consumed_at,created_at,version',
        'id,room_record_id,kind,party_id,repair_case_id,inspection_id,issued_by_member_id,invalidated_at,invalidated_reason,failed_view_count,created_at,version',
        'id,room_record_id,side,member_id,status,connected_at,sharing_stopped_at,created_at,version',
        'id,kind,status,attempts,next_attempt_at,created_at,version'
    ];
    human_no_access_tables text[] := ARRAY[]::text[];
    -- Columns Human may read even though their names look sensitive. Reviewed reasons:
    -- keyed HMAC pseudonyms (phone_hmac; linkable to each other, not reversible without the server key),
    -- record hash-chain values (including the chain head's last_hash in state), timestamps/flags, region and part lookup codes/labels (room_part.name is user input),
    -- small action payload values (today: answer, consentVersion, photo digests; open map, review new keys).
    human_approved_sensitive_columns text[] := ARRAY[
        'log.deletion_log.action_hash',
        'log.deletion_log.phone_hmac',
        'log.deletion_log.prev_hash',
        'record.action.hash',
        'record.action.hash_version',
        'record.action.payload',
        'record.action.prev_hash',
        'record.photo.device_taken_at',
        'state.action_chain_head.last_hash',
        'state.member.phone_verified_at',
        'state.part_template.name',
        'state.region.code',
        'state.room_part.name',
        'state.room_record.region_code',
        'state.sms_opt_out.phone_hmac'
    ];
    human_visible_sensitive text[];
    human_table text;
    human_index int;
BEGIN
    -- The executor must take this transaction-level lock in the same transaction before running this block.
    -- An autocommit caller would have released it already, so this also proves one explicit transaction.
    IF NOT EXISTS (
        SELECT 1 FROM pg_locks
        WHERE locktype = 'advisory' AND pid = pg_backend_pid()
          AND classid = 1752392040 AND objid = 156 AND objsubid = 2 AND granted
    ) THEN
        RAISE EXCEPTION 'grants must run inside the transaction that holds the grants advisory lock';
    END IF;
    IF current_user = session_user THEN
        RAISE EXCEPTION 'grants must run after SET ROLE from the migration login to the object owner';
    END IF;
    IF app_role IS NULL OR purge_role IS NULL OR human_role IS NULL THEN
        RAISE EXCEPTION 'required homestamp.grants role settings are missing';
    END IF;
    IF owner_role IN (app_role, purge_role, human_role)
       OR app_role IN (purge_role, human_role)
       OR purge_role = human_role
       OR session_user IN (app_role, purge_role, human_role) THEN
        RAISE EXCEPTION 'migration, owner, app, purge, and human roles must be distinct';
    END IF;
    IF EXISTS (
        SELECT 1 FROM (VALUES (owner_role), (app_role), (purge_role), (human_role)) AS expected(role_name)
        LEFT JOIN pg_roles r ON r.rolname = expected.role_name
        WHERE r.oid IS NULL
    ) THEN
        RAISE EXCEPTION 'owner, app, purge, and human roles must exist before grants are applied';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = owner_role AND NOT rolcanlogin
    ) THEN
        RAISE EXCEPTION 'current object owner role must be NOLOGIN';
    END IF;
    IF NOT pg_has_role(session_user, owner_role, 'SET') THEN
        RAISE EXCEPTION 'session login cannot SET ROLE to the object owner';
    END IF;
    -- USAGE means the owner's privileges are usable without SET ROLE (inherited); SET only allows switching.
    IF pg_has_role(session_user, owner_role, 'USAGE')
       OR pg_has_role(session_user, owner_role, 'MEMBER WITH ADMIN OPTION') THEN
        RAISE EXCEPTION 'migration login must not inherit or administer the object owner role';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = session_user AND rolcanlogin AND NOT rolsuper
          AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit
    ) THEN
        RAISE EXCEPTION 'grants must be applied by a non-superuser migration login';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname IN (app_role, purge_role, human_role)
          AND (rolsuper OR rolcreatedb OR rolcreaterole)
    ) THEN
        RAISE EXCEPTION 'app, purge, and human roles must not have administrative attributes';
    END IF;
    IF pg_has_role(app_role, owner_role, 'MEMBER')
       OR pg_has_role(purge_role, owner_role, 'MEMBER')
       OR pg_has_role(human_role, owner_role, 'MEMBER') THEN
        RAISE EXCEPTION 'operational roles must not inherit owner membership';
    END IF;
    IF pg_has_role(app_role, purge_role, 'MEMBER')
       OR pg_has_role(app_role, human_role, 'MEMBER')
       OR pg_has_role(purge_role, app_role, 'MEMBER')
       OR pg_has_role(purge_role, human_role, 'MEMBER')
       OR pg_has_role(human_role, app_role, 'MEMBER')
       OR pg_has_role(human_role, purge_role, 'MEMBER') THEN
        RAISE EXCEPTION 'app, purge, and human roles must not inherit one another';
    END IF;
    IF has_database_privilege(app_role, current_database(), 'CREATE')
       OR has_database_privilege(purge_role, current_database(), 'CREATE')
       OR has_database_privilege(human_role, current_database(), 'CREATE') THEN
        RAISE EXCEPTION 'operational roles must not have database CREATE';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_namespace n
        CROSS JOIN LATERAL aclexplode(COALESCE(n.nspacl, acldefault('n', n.nspowner))) a
        WHERE n.nspname = 'public' AND a.grantee = 0
          AND a.privilege_type IN ('USAGE', 'CREATE')
    ) OR has_schema_privilege(app_role, 'public', 'USAGE')
      OR has_schema_privilege(app_role, 'public', 'CREATE')
      OR has_schema_privilege(purge_role, 'public', 'USAGE')
      OR has_schema_privilege(purge_role, 'public', 'CREATE')
      OR has_schema_privilege(human_role, 'public', 'USAGE')
      OR has_schema_privilege(human_role, 'public', 'CREATE') THEN
        RAISE EXCEPTION
            'public schema must not grant USAGE or CREATE to PUBLIC or operational roles; adjust it as the schema owner or database administrator';
    END IF;

    IF EXISTS (
        SELECT 1 FROM (VALUES ('record'), ('state'), ('log')) AS expected(schema_name)
        LEFT JOIN pg_namespace n ON n.nspname = expected.schema_name
        WHERE n.oid IS NULL OR n.nspowner <> (SELECT oid FROM pg_roles WHERE rolname = owner_role)
    ) THEN
        RAISE EXCEPTION 'record, state, and log schemas must be owned by the current owner role';
    END IF;

    SELECT array_agg(format('%I.%I', n.nspname, c.relname) ORDER BY n.nspname, c.relname)
      INTO actual_tables
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind IN ('r', 'p') AND n.nspname IN ('record', 'state', 'log');
    IF actual_tables IS DISTINCT FROM (
        SELECT array_agg(table_name ORDER BY table_name) FROM unnest(expected_tables) table_name
    ) THEN
        RAISE EXCEPTION 'unclassified or missing table inventory: %. If a new V file added, renamed, or dropped a table, '
            'update expected_tables, the Human lists, and the C-table UPDATE/DELETE grants in db/grants/grants.sql together',
            actual_tables;
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'public.flyway_schema_history must exist before grants are applied';
    END IF;
    SELECT array_agg(format('%I.%I', n.nspname, c.relname) ORDER BY c.relname)
      INTO public_tables
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind IN ('r', 'p') AND n.nspname = 'public';
    IF public_tables IS DISTINCT FROM ARRAY['public.flyway_schema_history']::text[] THEN
        RAISE EXCEPTION 'public schema contains an unclassified table: %', public_tables;
    END IF;
    IF (SELECT r.rolname
        FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
        WHERE c.oid = 'public.flyway_schema_history'::regclass) <> owner_role THEN
        RAISE EXCEPTION 'public.flyway_schema_history must be owned by the current owner role';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_roles r ON r.oid = c.relowner
        WHERE c.relkind IN ('r', 'p') AND n.nspname IN ('record', 'state', 'log')
          AND r.rolname <> owner_role
    ) THEN
        RAISE EXCEPTION 'all record, state, and log tables must be owned by the current owner role';
    END IF;

    SELECT array_agg(format('%I.%I(%s)', n.nspname, p.proname,
                            oidvectortypes(p.proargtypes)) ORDER BY n.nspname, p.proname)
      INTO actual_functions
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname IN ('record', 'state', 'log');
    IF actual_functions IS DISTINCT FROM (
        SELECT array_agg(function_name ORDER BY function_name) FROM unnest(expected_functions) function_name
    ) THEN
        RAISE EXCEPTION 'unclassified or missing function inventory: %', actual_functions;
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        JOIN pg_roles r ON r.oid = p.proowner
        WHERE n.nspname IN ('record', 'state', 'log') AND r.rolname <> owner_role
    ) THEN
        RAISE EXCEPTION 'all record, state, and log functions must be owned by the current owner role';
    END IF;

    IF (SELECT array_agg(x ORDER BY x) FROM unnest(human_full_tables || human_column_tables || human_no_access_tables) x)
       IS DISTINCT FROM (SELECT array_agg(x ORDER BY x) FROM unnest(expected_tables) x)
       OR cardinality(human_column_tables) <> cardinality(human_column_lists) THEN
        RAISE EXCEPTION 'every table must be classified exactly once for the human role';
    END IF;
    -- Any column Human could read whose name looks like a credential or contact value must be explicitly approved.
    SELECT array_agg(format('%s.%s', table_name, column_name) ORDER BY table_name, column_name)
      INTO human_visible_sensitive FROM (
        SELECT a.attrelid::regclass::text AS table_name, a.attname::text AS column_name
          FROM pg_attribute a
         WHERE a.attrelid = ANY (SELECT t::regclass FROM unnest(human_full_tables) t)
           AND a.attnum > 0 AND NOT a.attisdropped
        UNION
        SELECT human_column_tables[i], c
          FROM generate_subscripts(human_column_tables, 1) i,
               LATERAL unnest(string_to_array(human_column_lists[i], ',')) c
    ) visible
    WHERE (column_name ~ '(phone|email|mail|password|passwd|token|secret|hash|hmac|otp|credential|code|payload|device|name|address|account)' OR column_name ~ '(^|_)ip(_|$)')
      AND format('%s.%s', table_name, column_name) <> ALL (human_approved_sensitive_columns);
    IF human_visible_sensitive IS NOT NULL THEN
        RAISE EXCEPTION 'human role would see unapproved sensitive-looking columns: %', human_visible_sensitive;
    END IF;

    -- Clear any older global defaults first; per-schema REVOKE cannot cancel a global grant.
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON TABLES FROM PUBLIC, %I, %I, %I',
        owner_role, app_role, purge_role, human_role);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON SEQUENCES FROM PUBLIC, %I, %I, %I',
        owner_role, app_role, purge_role, human_role);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON FUNCTIONS FROM PUBLIC, %I, %I, %I',
        owner_role, app_role, purge_role, human_role);

    FOREACH schema_name IN ARRAY ARRAY['record', 'state', 'log'] LOOP
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM PUBLIC, %I, %I, %I',
            owner_role, schema_name, app_role, purge_role, human_role);
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM PUBLIC, %I, %I, %I',
            owner_role, schema_name, app_role, purge_role, human_role);
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, %I, %I, %I',
            owner_role, schema_name, app_role, purge_role, human_role);
    END LOOP;

    EXECUTE format('REVOKE ALL ON SCHEMA record, state, log FROM PUBLIC, %I, %I, %I',
                   app_role, purge_role, human_role);
    EXECUTE format('GRANT USAGE ON SCHEMA record, state, log TO %I, %I, %I',
                   app_role, purge_role, human_role);

    REVOKE ALL ON ALL TABLES IN SCHEMA record, state, log FROM PUBLIC;
    REVOKE ALL ON TABLE public.flyway_schema_history FROM PUBLIC;
    EXECUTE format('REVOKE ALL ON TABLE public.flyway_schema_history FROM %I, %I, %I',
                   app_role, purge_role, human_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA record, state, log FROM %I, %I, %I',
                   app_role, purge_role, human_role);
    REVOKE ALL ON ALL SEQUENCES IN SCHEMA record, state, log FROM PUBLIC;
    EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA record, state, log FROM %I, %I, %I',
                   app_role, purge_role, human_role);
    REVOKE ALL ON ALL FUNCTIONS IN SCHEMA record, state, log FROM PUBLIC;
    EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA record, state, log FROM %I, %I, %I',
                   app_role, purge_role, human_role);

    EXECUTE format('GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA record TO %I', app_role);
    EXECUTE format('GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA state TO %I', app_role);
    -- Only classified mutable C tables receive UPDATE/DELETE; new state tables stay append-only.
    EXECUTE format(
        'GRANT UPDATE, DELETE ON TABLE state.correction_request, state.in_app_notification, '
        'state.inspection, state.inspection_item, state.link, state.member, state.notice, '
        'state.outbox, state.party, state.payment, state.phone_verification, '
        'state.photo_visibility, state.progress_note_response, state.rate_counter, '
        'state.repair_case, state.refresh_token, state.response_request, state.room_part, '
        'state.settlement, state.settlement_item, state.settlement_transfer TO %I',
        app_role);
    EXECUTE format('GRANT DELETE ON TABLE state.sms_opt_out TO %I', app_role);
    EXECUTE format('GRANT UPDATE ON TABLE state.room_record TO %I', app_role);
    -- Chain head (05 2-3, V9): the app advances last_seq/last_hash but never deletes it, so a deleted last action
    -- cannot make the next action reuse its room_seq. It goes away only with the whole room (CASCADE from purge).
    EXECUTE format('GRANT UPDATE ON TABLE state.action_chain_head TO %I', app_role);
    EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON TABLE state.part_template, state.region FROM %I', app_role);
    EXECUTE format('REVOKE UPDATE ON TABLE state.sms_opt_out FROM %I', app_role);
    EXECUTE format('REVOKE DELETE ON TABLE state.room_record FROM %I', app_role);
    EXECUTE format('GRANT INSERT ON TABLE log.deletion_log TO %I', app_role);

    EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA record, state, log TO %I', purge_role);
    -- Human: table-level SELECT only on the explicit list; column-level SELECT on sensitive tables.
    -- REVOKE ALL ON ALL TABLES above also removes column privileges, so reruns start from nothing.
    FOREACH human_table IN ARRAY human_full_tables LOOP
        EXECUTE format('GRANT SELECT ON TABLE %s TO %I', human_table::regclass, human_role);
    END LOOP;
    FOR human_index IN 1 .. cardinality(human_column_tables) LOOP
        EXECUTE format('GRANT SELECT (%s) ON TABLE %s TO %I',
                       (SELECT string_agg(quote_ident(c), ', ')
                          FROM unnest(string_to_array(human_column_lists[human_index], ',')) c),
                       human_column_tables[human_index]::regclass, human_role);
    END LOOP;
    EXECUTE format('GRANT INSERT ON TABLE log.deletion_log, state.outbox TO %I', purge_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION state.purge_room_record(uuid) TO %I', purge_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION record.purge_member_acknowledgements(uuid, timestamptz) TO %I',
                   app_role);

    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA record GRANT SELECT, INSERT ON TABLES TO %I',
                   owner_role, app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA state GRANT SELECT, INSERT ON TABLES TO %I',
                   owner_role, app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA log GRANT INSERT ON TABLES TO %I',
                   owner_role, app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA record GRANT SELECT ON TABLES TO %I',
                   owner_role, purge_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA state GRANT SELECT ON TABLES TO %I',
                   owner_role, purge_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA log GRANT SELECT ON TABLES TO %I',
                   owner_role, purge_role);
    -- No default SELECT for Human: a new table stays unreadable until it is classified above.

    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA record, state, log TO %I', app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA record GRANT USAGE, SELECT ON SEQUENCES TO %I',
                   owner_role, app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA state GRANT USAGE, SELECT ON SEQUENCES TO %I',
                   owner_role, app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA log GRANT USAGE, SELECT ON SEQUENCES TO %I',
                   owner_role, app_role);

    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC', owner_role);
END
$$;
