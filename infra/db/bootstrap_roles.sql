\set ON_ERROR_STOP on
\set QUIET 1

-- Required psql variables: owner_role, migration_role, app_role, purge_role, human_role.
-- human_role is the NOLOGIN Human read group (e.g. homestamp_human_read). Per-person LOGIN roles
-- are created by the separate admin procedure in docs/runbooks, never by this script.
-- Role names are deployment inputs; credentials are intentionally not handled here.
SELECT set_config('homestamp.bootstrap.owner_role', :'owner_role', false);
SELECT set_config('homestamp.bootstrap.migration_role', :'migration_role', false);
SELECT set_config('homestamp.bootstrap.app_role', :'app_role', false);
SELECT set_config('homestamp.bootstrap.purge_role', :'purge_role', false);
SELECT set_config('homestamp.bootstrap.human_role', :'human_role', false);

BEGIN;

DO $$
DECLARE
    owner_role text := current_setting('homestamp.bootstrap.owner_role');
    migration_role text := current_setting('homestamp.bootstrap.migration_role');
    app_role text := current_setting('homestamp.bootstrap.app_role');
    purge_role text := current_setting('homestamp.bootstrap.purge_role');
    human_role text := current_setting('homestamp.bootstrap.human_role');
    role_spec record;
    existing_role pg_roles%ROWTYPE;
    role_ids oid[];
    owner_id oid;
    migration_id oid;
    membership record;
    application_role text;
    public_schema_id oid;
    schema_name text;
BEGIN
    IF owner_role IS NULL OR migration_role IS NULL OR app_role IS NULL
       OR purge_role IS NULL OR human_role IS NULL THEN
        RAISE EXCEPTION 'all five role names are required';
    END IF;

    IF owner_role !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR migration_role !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR app_role !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR purge_role !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR human_role !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR owner_role LIKE 'pg\_%' ESCAPE '\'
       OR migration_role LIKE 'pg\_%' ESCAPE '\'
       OR app_role LIKE 'pg\_%' ESCAPE '\'
       OR purge_role LIKE 'pg\_%' ESCAPE '\'
       OR human_role LIKE 'pg\_%' ESCAPE '\' THEN
        RAISE EXCEPTION 'role names must be lowercase PostgreSQL identifiers and cannot use the pg_ prefix';
    END IF;

    IF cardinality(ARRAY[owner_role, migration_role, app_role, purge_role, human_role]) <>
       cardinality(ARRAY(SELECT DISTINCT unnest(ARRAY[owner_role, migration_role, app_role, purge_role, human_role]))) THEN
        RAISE EXCEPTION 'owner, migration, app, purge, and human roles must be distinct';
    END IF;

    IF current_user IN (owner_role, migration_role, app_role, purge_role, human_role)
       OR session_user IN (owner_role, migration_role, app_role, purge_role, human_role) THEN
        RAISE EXCEPTION 'bootstrap must run as a separate database administrator';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = current_user AND (rolsuper OR rolcreaterole)
    ) THEN
        RAISE EXCEPTION 'bootstrap requires a superuser or CREATEROLE administrator';
    END IF;

    FOR role_spec IN
        SELECT * FROM (VALUES
            (owner_role, false),
            (migration_role, true),
            (app_role, true),
            (purge_role, true),
            (human_role, false)
        ) AS expected(role_name, can_login)
    LOOP
        SELECT * INTO existing_role FROM pg_roles WHERE rolname = role_spec.role_name;
        IF NOT FOUND THEN
            EXECUTE format(
                'CREATE ROLE %I WITH %s NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
                role_spec.role_name,
                CASE WHEN role_spec.can_login THEN 'LOGIN' ELSE 'NOLOGIN' END
            );
        ELSIF existing_role.rolcanlogin <> role_spec.can_login
           OR existing_role.rolinherit
           OR existing_role.rolsuper
           OR existing_role.rolcreatedb
           OR existing_role.rolcreaterole
           OR existing_role.rolreplication
           OR existing_role.rolbypassrls
           OR existing_role.rolconnlimit <> -1
           OR existing_role.rolconfig IS NOT NULL THEN
            RAISE EXCEPTION 'existing role % has unexpected attributes; refusing to alter it', role_spec.role_name;
        END IF;
    END LOOP;

    SELECT oid INTO owner_id FROM pg_roles WHERE rolname = owner_role;
    SELECT oid INTO migration_id FROM pg_roles WHERE rolname = migration_role;
    SELECT array_agg(oid) INTO role_ids FROM pg_roles
    WHERE rolname IN (owner_role, migration_role, app_role, purge_role, human_role);

    -- Refuse all unexpected memberships touching these roles. A non-superuser
    -- CREATEROLE executor receives an ADMIN-only membership when it creates a role.
    FOR membership IN
        SELECT member_role.rolname AS member_name,
               granted_role.rolname AS granted_name,
               m.inherit_option,
               m.set_option,
               m.admin_option
        FROM pg_auth_members m
        JOIN pg_roles member_role ON member_role.oid = m.member
        JOIN pg_roles granted_role ON granted_role.oid = m.roleid
        WHERE m.member = ANY(role_ids) OR m.roleid = ANY(role_ids)
    LOOP
        IF membership.member_name = migration_role
           AND membership.granted_name = owner_role
           AND NOT membership.inherit_option
           AND membership.set_option
           AND NOT membership.admin_option THEN
            CONTINUE;
        END IF;
        IF membership.member_name = current_user
           AND membership.granted_name IN (owner_role, migration_role, app_role, purge_role, human_role)
           AND NOT membership.inherit_option
           AND NOT membership.set_option
           AND membership.admin_option THEN
            CONTINUE;
        END IF;
        -- A per-person LOGIN role may belong to the Human read group with INHERIT TRUE, SET FALSE,
        -- ADMIN FALSE only. Its other memberships and direct privileges are checked by
        -- infra/db/check_human_access.sql.
        IF membership.granted_name = human_role
           AND membership.member_name NOT IN (owner_role, migration_role, app_role, purge_role, human_role)
           AND membership.member_name <> current_user
           AND membership.inherit_option
           AND NOT membership.set_option
           AND NOT membership.admin_option THEN
            CONTINUE;
        END IF;
        RAISE EXCEPTION 'unexpected role membership between % and %; refusing to change it',
            membership.member_name, membership.granted_name;
    END LOOP;

    IF pg_has_role(app_role, owner_role, 'MEMBER')
       OR pg_has_role(purge_role, owner_role, 'MEMBER')
       OR pg_has_role(human_role, owner_role, 'MEMBER') THEN
        RAISE EXCEPTION 'app, purge, and human roles must not be members of owner';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_auth_members
        WHERE member = migration_id AND roleid = owner_id
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM pg_auth_members
            WHERE member = migration_id AND roleid = owner_id
              AND NOT inherit_option AND set_option AND NOT admin_option
        ) THEN
            RAISE EXCEPTION 'migration-to-owner membership must be SET-only; refusing to alter it';
        END IF;
    ELSE
        EXECUTE format(
            'GRANT %I TO %I WITH ADMIN FALSE, INHERIT FALSE, SET TRUE',
            owner_role,
            migration_role
        );
    END IF;

    IF NOT pg_has_role(migration_role, owner_role, 'SET')
       OR pg_has_role(migration_role, owner_role, 'USAGE')
       OR pg_has_role(migration_role, owner_role, 'SET WITH ADMIN OPTION') THEN
        RAISE EXCEPTION 'migration role must be able to SET ROLE owner without inheriting or regranting it';
    END IF;

    FOREACH application_role IN ARRAY ARRAY[migration_role, app_role, purge_role, human_role]
    LOOP
        IF has_database_privilege(application_role, current_database(), 'CREATE') THEN
            RAISE EXCEPTION 'login role % already has database CREATE; refusing to change it', application_role;
        END IF;
    END LOOP;

    EXECUTE format('GRANT CREATE ON DATABASE %I TO %I', current_database(), owner_role);
    EXECUTE format('REVOKE TEMP ON DATABASE %I FROM PUBLIC', current_database());
    FOREACH application_role IN ARRAY ARRAY[migration_role, app_role, purge_role, human_role]
    LOOP
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), application_role);
        IF has_database_privilege(application_role, current_database(), 'TEMP') THEN
            RAISE EXCEPTION 'login role % still has database TEMP; refusing to continue', application_role;
        END IF;
    END LOOP;

    SELECT oid INTO public_schema_id FROM pg_namespace WHERE nspname = 'public';
    IF public_schema_id IS NULL THEN
        RAISE EXCEPTION 'public schema is missing';
    END IF;

    -- The ACL check below skips the schema owner's implicit privileges, so an application or
    -- Human role must never own public. Ownership is not transferred automatically.
    IF EXISTS (
        SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
        WHERE n.oid = public_schema_id
          AND (r.rolname IN (migration_role, app_role, purge_role, human_role)
               OR EXISTS (
                   SELECT 1 FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
                   WHERE m.member = r.oid AND g.rolname = human_role))
    ) THEN
        RAISE EXCEPTION 'public schema is owned by a migration, app, purge, or human role; ownership migration is not performed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_namespace n
        CROSS JOIN LATERAL aclexplode(COALESCE(n.nspacl, acldefault('n', n.nspowner))) a
        WHERE n.oid = public_schema_id
          AND a.grantee <> 0
          AND a.grantee <> n.nspowner
          AND a.grantee <> owner_id
    ) THEN
        RAISE EXCEPTION 'public schema has direct grants to an unexpected role; review before bootstrap';
    END IF;

    EXECUTE 'REVOKE ALL ON SCHEMA public FROM PUBLIC';
    EXECUTE format('GRANT USAGE, CREATE ON SCHEMA public TO %I', owner_role);

    IF EXISTS (
        SELECT 1 FROM pg_class c
        WHERE c.relnamespace = public_schema_id
          AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
          AND c.relname <> 'flyway_schema_history'
    ) THEN
        RAISE EXCEPTION 'public schema contains unexpected application objects';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_class c
        WHERE c.relnamespace = public_schema_id
          AND c.relname = 'flyway_schema_history'
          AND c.relowner <> owner_id
    ) THEN
        RAISE EXCEPTION 'public.flyway_schema_history is not owned by owner; ownership migration is not performed';
    END IF;

    FOREACH schema_name IN ARRAY ARRAY['record', 'state', 'log']
    LOOP
        IF EXISTS (
            SELECT 1 FROM pg_namespace n
            WHERE n.nspname = schema_name AND n.nspowner <> owner_id
        ) THEN
            RAISE EXCEPTION 'schema % is not owned by owner; ownership migration is not performed', schema_name;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = schema_name AND c.relowner <> owner_id
        ) OR EXISTS (
            SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = schema_name AND p.proowner <> owner_id
        ) OR EXISTS (
            SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = schema_name AND t.typowner <> owner_id
        ) THEN
            RAISE EXCEPTION 'schema % contains objects not owned by owner; ownership migration is not performed', schema_name;
        END IF;
    END LOOP;
END
$$;

COMMIT;
