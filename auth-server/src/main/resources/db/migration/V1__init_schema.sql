-- 초기 스키마 (직원 범위). 기준: docs/data-model.md
-- partner_profile, customer_profile, external_identity는 이후 버전에서 추가한다.

CREATE TABLE principal
(
    id                 uuid        NOT NULL DEFAULT uuidv7(),
    type               varchar(20) NOT NULL,
    status             varchar(20) NOT NULL,
    failed_login_count int         NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deactivated_at     timestamptz,
    CONSTRAINT pk_principal PRIMARY KEY (id),
    CONSTRAINT ck_principal_type CHECK (type IN ('EMPLOYEE', 'SYSTEM', 'PARTNER', 'CUSTOMER')),
    CONSTRAINT ck_principal_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

CREATE TABLE employee_profile
(
    principal_id uuid         NOT NULL,
    email        varchar(254) NOT NULL,
    name         varchar(50)  NOT NULL,
    phone        varchar(20),
    address      varchar(255),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_employee_profile PRIMARY KEY (principal_id),
    CONSTRAINT fk_employee_profile_principal FOREIGN KEY (principal_id) REFERENCES principal (id)
);

CREATE UNIQUE INDEX uq_employee_profile_email ON employee_profile (lower(email));

-- 테이블만 만든다. 기능은 추후.
CREATE TABLE system_client
(
    principal_id       uuid         NOT NULL,
    client_id          varchar(100) NOT NULL,
    client_secret_hash char(64),
    name               varchar(100) NOT NULL,
    secret_rotated_at  timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_system_client PRIMARY KEY (principal_id),
    CONSTRAINT fk_system_client_principal FOREIGN KEY (principal_id) REFERENCES principal (id),
    CONSTRAINT uq_system_client_client_id UNIQUE (client_id)
);

CREATE TABLE password_credential
(
    principal_id  uuid         NOT NULL,
    password_hash varchar(255) NOT NULL,
    changed_at    timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_password_credential PRIMARY KEY (principal_id),
    CONSTRAINT fk_password_credential_principal FOREIGN KEY (principal_id) REFERENCES principal (id)
);

CREATE TABLE verification
(
    id             bigint       GENERATED ALWAYS AS IDENTITY,
    principal_id   uuid         NOT NULL,
    purpose        varchar(30)  NOT NULL,
    method         varchar(10)  NOT NULL,
    target         varchar(254) NOT NULL,
    token_hash     char(64)     NOT NULL,
    payload        jsonb,
    attempt_count  int          NOT NULL DEFAULT 0,
    max_attempts   int,
    expires_at     timestamptz  NOT NULL,
    consumed_at    timestamptz,
    invalidated_at timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_verification PRIMARY KEY (id),
    CONSTRAINT fk_verification_principal FOREIGN KEY (principal_id) REFERENCES principal (id),
    CONSTRAINT uq_verification_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_verification_purpose CHECK (purpose IN
        ('EMPLOYEE_INVITATION', 'SIGNUP_VERIFICATION', 'PASSWORD_RESET', 'OWNER_TRANSFER', 'EMAIL_CHANGE')),
    CONSTRAINT ck_verification_method CHECK (method IN ('EMAIL'))
);

-- 살아 있는 토큰은 (principal, purpose)마다 하나 (VER-03)
CREATE UNIQUE INDEX uq_verification_live ON verification (principal_id, purpose)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE audience
(
    id          bigint       GENERATED ALWAYS AS IDENTITY,
    code        varchar(30)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audience PRIMARY KEY (id),
    CONSTRAINT uq_audience_code UNIQUE (code),
    CONSTRAINT ck_audience_code CHECK (code ~ '^[a-z][a-z0-9_]*$')
);

CREATE TABLE role
(
    id          bigint       GENERATED ALWAYS AS IDENTITY,
    audience_id bigint       NOT NULL,
    code        varchar(50)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    is_system   boolean      NOT NULL DEFAULT false,
    created_by  uuid,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT fk_role_audience FOREIGN KEY (audience_id) REFERENCES audience (id),
    CONSTRAINT fk_role_created_by FOREIGN KEY (created_by) REFERENCES principal (id),
    CONSTRAINT uq_role_audience_code UNIQUE (audience_id, code),
    CONSTRAINT ck_role_code CHECK (code ~ '^[a-z][a-z0-9_]*$')
);

CREATE TABLE principal_role
(
    principal_id uuid        NOT NULL,
    role_id      bigint      NOT NULL,
    granted_by   uuid,
    granted_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_principal_role PRIMARY KEY (principal_id, role_id),
    CONSTRAINT fk_principal_role_principal FOREIGN KEY (principal_id) REFERENCES principal (id),
    CONSTRAINT fk_principal_role_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_principal_role_granted_by FOREIGN KEY (granted_by) REFERENCES principal (id)
);

CREATE TABLE refresh_session
(
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    principal_id        uuid        NOT NULL,
    realm               varchar(20) NOT NULL,
    current_token_hash  char(64)    NOT NULL,
    previous_token_hash char(64),
    rotated_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    last_used_at        timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at          timestamptz,
    revoke_reason       varchar(30),
    user_agent          varchar(255),
    ip                  inet,
    CONSTRAINT pk_refresh_session PRIMARY KEY (id),
    CONSTRAINT fk_refresh_session_principal FOREIGN KEY (principal_id) REFERENCES principal (id),
    CONSTRAINT uq_refresh_session_current_token_hash UNIQUE (current_token_hash),
    CONSTRAINT ck_refresh_session_realm CHECK (realm IN ('INTERNAL', 'PARTNER', 'CUSTOMER')),
    CONSTRAINT ck_refresh_session_revoke_reason CHECK (revoke_reason IN
        ('LOGOUT', 'REUSE_DETECTED', 'PASSWORD_RESET', 'PASSWORD_CHANGED', 'ACCOUNT_SUSPENDED',
         'ACCOUNT_DEACTIVATED', 'OWNER_TRANSFERRED', 'OWNER_RECOVERY'))
);

CREATE INDEX ix_refresh_session_previous_token_hash ON refresh_session (previous_token_hash)
    WHERE previous_token_hash IS NOT NULL;
CREATE INDEX ix_refresh_session_principal_live ON refresh_session (principal_id)
    WHERE revoked_at IS NULL;

-- FK를 두지 않는다 (AUD-06)
CREATE TABLE audit_log
(
    id          bigint      GENERATED ALWAYS AS IDENTITY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id    uuid,
    actor_type  varchar(20),
    action      varchar(50) NOT NULL,
    target_type varchar(30),
    target_id   varchar(50),
    detail      jsonb,
    ip          inet,
    user_agent  varchar(255),
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_target_type CHECK (target_type IN ('PRINCIPAL', 'ROLE', 'AUDIENCE', 'SESSION'))
);

CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_id, occurred_at);
CREATE INDEX ix_audit_log_target ON audit_log (target_type, target_id);

-- 초기 데이터
INSERT INTO audience (code, name)
VALUES ('wms', 'WMS'),
       ('catalog', 'Catalog'),
       ('store', 'Store'),
       ('auth', 'Auth');

-- system role의 id는 고정한다 (owner 유일성 인덱스가 id를 쓴다)
INSERT INTO role (id, audience_id, code, name, is_system)
OVERRIDING SYSTEM VALUE
SELECT 1, id, 'owner', 'Owner', true FROM audience WHERE code = 'auth';
INSERT INTO role (id, audience_id, code, name, is_system)
OVERRIDING SYSTEM VALUE
SELECT 2, id, 'admin', 'Admin', true FROM audience WHERE code = 'auth';
SELECT setval(pg_get_serial_sequence('role', 'id'), 2);

-- owner는 한 명만 존재 (GOV-10). 1은 위에서 고정한 auth:owner의 id
CREATE UNIQUE INDEX uq_principal_role_owner ON principal_role (role_id) WHERE role_id = 1;
