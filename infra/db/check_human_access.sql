\set ON_ERROR_STOP on
\set QUIET 1

-- Read-only audit of the Human read group and every per-person LOGIN role in it.
-- Required psql variable: human_role (the NOLOGIN read group, e.g. homestamp_human_read).
-- Run as a trusted DB administrator after adding or removing a person and before each
-- production deployment. It changes nothing; any violation fails with a non-zero exit code.
SELECT set_config('homestamp.audit.human_role', :'human_role', false);

BEGIN READ ONLY;

DO $$
DECLARE
    human_role text := current_setting('homestamp.audit.human_role');
    group_id oid;
    person record;
    people integer := 0;
BEGIN
    SELECT oid INTO group_id FROM pg_roles WHERE rolname = human_role;
    IF group_id IS NULL THEN
        RAISE EXCEPTION 'human read group % does not exist', human_role;
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_roles WHERE oid = group_id
          AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
    ) THEN
        RAISE EXCEPTION 'human read group % must be NOLOGIN without administrative attributes', human_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = group_id) THEN
        RAISE EXCEPTION 'human read group % must not be a member of any role', human_role;
    END IF;

    FOR person IN
        SELECT r.oid, r.rolname, r.rolcanlogin, r.rolinherit, r.rolsuper, r.rolcreatedb,
               r.rolcreaterole, r.rolreplication, r.rolbypassrls, r.rolvaliduntil,
               m.inherit_option, m.set_option, m.admin_option
        FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
        WHERE m.roleid = group_id
    LOOP
        -- PostgreSQL 16+ gives a CREATEROLE creator an ADMIN-only membership (no INHERIT, no SET).
        -- That is the trusted administrator, not a person. ADMIN on anyone else is a violation.
        IF person.admin_option AND NOT person.inherit_option AND NOT person.set_option
           AND (person.rolsuper OR person.rolcreaterole) THEN
            CONTINUE;
        END IF;
        people := people + 1;
        IF NOT person.rolcanlogin OR person.rolinherit OR person.rolsuper OR person.rolcreatedb
           OR person.rolcreaterole OR person.rolreplication OR person.rolbypassrls THEN
            RAISE EXCEPTION 'person role % must be LOGIN NOINHERIT without administrative attributes',
                person.rolname;
        END IF;
        IF person.rolvaliduntil IS NULL THEN
            RAISE EXCEPTION 'person role % has no VALID UNTIL', person.rolname;
        END IF;
        IF NOT person.inherit_option OR person.set_option OR person.admin_option THEN
            RAISE EXCEPTION 'person role % membership must be INHERIT TRUE, SET FALSE, ADMIN FALSE',
                person.rolname;
        END IF;
        -- Only the Human group may be granted to a person (no pg_read_all_data, owner, app, ...).
        IF EXISTS (
            SELECT 1 FROM pg_auth_members WHERE member = person.oid AND roleid <> group_id
        ) THEN
            RAISE EXCEPTION 'person role % has a membership other than the human read group',
                person.rolname;
        END IF;
        -- Nobody may use the person role, except the ADMIN-only grant a CREATEROLE creator receives.
        IF EXISTS (
            SELECT 1 FROM pg_auth_members
            WHERE roleid = person.oid AND (inherit_option OR set_option OR NOT admin_option)
        ) THEN
            RAISE EXCEPTION 'another role can use person role %', person.rolname;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_class WHERE relowner = person.oid)
           OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = person.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = person.oid)
           OR EXISTS (SELECT 1 FROM pg_type WHERE typowner = person.oid)
           OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = person.oid) THEN
            RAISE EXCEPTION 'person role % owns database objects', person.rolname;
        END IF;
        -- Privileges come only from the group. Direct grants are not revoked by grants.sql.
        IF EXISTS (
            SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
            WHERE a.grantee = person.oid
        ) OR EXISTS (
            SELECT 1 FROM pg_attribute t CROSS JOIN LATERAL aclexplode(t.attacl) a
            WHERE a.grantee = person.oid
        ) OR EXISTS (
            SELECT 1 FROM pg_namespace n CROSS JOIN LATERAL aclexplode(n.nspacl) a
            WHERE a.grantee = person.oid
        ) OR EXISTS (
            SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(p.proacl) a
            WHERE a.grantee = person.oid
        ) OR EXISTS (
            SELECT 1 FROM pg_database d CROSS JOIN LATERAL aclexplode(d.datacl) a
            WHERE a.grantee = person.oid
        ) OR EXISTS (
            SELECT 1 FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) a
            WHERE a.grantee = person.oid OR d.defaclrole = person.oid
        ) THEN
            RAISE EXCEPTION 'person role % has direct privileges; grant only through the human read group',
                person.rolname;
        END IF;
    END LOOP;

    RAISE NOTICE 'human access audit passed: % person role(s) in %', people, human_role;
END
$$;

ROLLBACK;
