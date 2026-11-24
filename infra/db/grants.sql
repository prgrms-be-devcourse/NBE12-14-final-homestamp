\set ON_ERROR_STOP on

-- Run with psql as the migration login after Flyway has completed.
-- Required psql variables: owner_role, app_role, purge_role, human_role.
-- The owner role, role membership, schemas, tables, and functions are
-- provisioning/migration preconditions. This file never creates credentials
-- or changes object ownership. public schema ACLs must be prepared by its owner
-- or a database administrator before this script runs; this script only checks them.
SET ROLE :"owner_role";
SELECT set_config('homestamp.grants.app_role', :'app_role', false);
SELECT set_config('homestamp.grants.purge_role', :'purge_role', false);
SELECT set_config('homestamp.grants.human_role', :'human_role', false);

BEGIN;

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
BEGIN
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
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = session_user AND rolcanlogin AND NOT rolsuper
          AND NOT rolcreatedb AND NOT rolcreaterole
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
        RAISE EXCEPTION 'unclassified or missing table inventory: %', actual_tables;
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
    EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON TABLE state.part_template, state.region FROM %I', app_role);
    EXECUTE format('REVOKE UPDATE ON TABLE state.sms_opt_out FROM %I', app_role);
    EXECUTE format('REVOKE DELETE ON TABLE state.room_record FROM %I', app_role);
    EXECUTE format('GRANT INSERT ON TABLE log.deletion_log TO %I', app_role);

    EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA record, state, log TO %I, %I',
                   purge_role, human_role);
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
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA record GRANT SELECT ON TABLES TO %I',
                   owner_role, human_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA state GRANT SELECT ON TABLES TO %I',
                   owner_role, human_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA log GRANT SELECT ON TABLES TO %I',
                   owner_role, human_role);

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

COMMIT;
