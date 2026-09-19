-- SOSync initial schema.
--
-- Conventions borrowed from the Readers backend: NanoId primary keys stored as VARCHAR(21),
-- snake_case tables and columns, enums persisted as strings, state transitions recorded as
-- append-only events rather than overwritten.
--
-- Times are TIMESTAMPTZ throughout. An emergency timeline that is ambiguous about time zone
-- is worse than no timeline.

-- ═══════════════════════════════════════════════════════════════════════════════
-- Accounts
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE users (
    id                  VARCHAR(21)  PRIMARY KEY,
    full_name           VARCHAR(120) NOT NULL,
    phone               VARCHAR(20)  NOT NULL,
    email               VARCHAR(160),
    password_hash       VARCHAR(100) NOT NULL,
    role                VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    -- Cancelling an active emergency requires this PIN. Without it, whoever takes the phone
    -- can silently call off the response.
    safety_pin_hash     VARCHAR(100),
    -- Shown to a responder so they know who they are looking for. Deliberately short.
    emergency_note      VARCHAR(500),
    photo_url           VARCHAR(500),
    phone_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_users_phone ON users (phone);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX ix_users_role ON users (role);

-- Short-lived codes for phone verification and password reset.
CREATE TABLE verification_codes (
    id          VARCHAR(21)  PRIMARY KEY,
    user_id     VARCHAR(21)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     VARCHAR(32)  NOT NULL,
    code        VARCHAR(10)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_verification_codes_user ON verification_codes (user_id, purpose);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Trusted contacts
-- ═══════════════════════════════════════════════════════════════════════════════
-- A contact is stored by phone number, not by user id, so a user can nominate someone who has
-- not installed SOSync: that person still gets the SMS fallback. When the number later
-- registers, the match is made on the number, which is why numbers are normalised to E.164
-- on write.
CREATE TABLE emergency_contacts (
    id                   VARCHAR(21)  PRIMARY KEY,
    user_id              VARCHAR(21)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                 VARCHAR(120) NOT NULL,
    phone                VARCHAR(20)  NOT NULL,
    relationship         VARCHAR(60),
    priority             INT          NOT NULL DEFAULT 1,
    notification_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_emergency_contacts_user_phone ON emergency_contacts (user_id, phone);
CREATE INDEX ix_emergency_contacts_phone ON emergency_contacts (phone);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Responders
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE responders (
    id                  VARCHAR(21)  PRIMARY KEY,
    user_id             VARCHAR(21)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    organization        VARCHAR(160) NOT NULL,
    -- Verification is an administrator action, never self-service. An unverified responder
    -- receives no alerts at all.
    verification_status VARCHAR(32)  NOT NULL,
    availability_status VARCHAR(32)  NOT NULL,
    current_lat         DOUBLE PRECISION,
    current_lng         DOUBLE PRECISION,
    location_updated_at TIMESTAMPTZ,
    verified_at         TIMESTAMPTZ,
    verified_by         VARCHAR(21),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_responders_user ON responders (user_id);
CREATE INDEX ix_responders_dispatchable ON responders (verification_status, availability_status);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Incidents
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE incidents (
    id                    VARCHAR(21)  PRIMARY KEY,
    user_id               VARCHAR(21)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                VARCHAR(32)  NOT NULL,
    -- Silent mode: the reporting device shows and sounds nothing. The whole point is that the
    -- person causing the danger must not see the alarm being raised.
    silent                BOOLEAN      NOT NULL DEFAULT TRUE,
    note                  VARCHAR(500),

    -- The first GPS fix, kept on the incident so an alert renders without a join.
    trigger_lat           DOUBLE PRECISION,
    trigger_lng           DOUBLE PRECISION,
    trigger_accuracy      DOUBLE PRECISION,

    -- Denormalised latest position. Read on every alert refresh; a join per row would not hold up.
    last_lat              DOUBLE PRECISION,
    last_lng              DOUBLE PRECISION,
    last_location_at      TIMESTAMPTZ,

    assigned_responder_id VARCHAR(21) REFERENCES responders (id),
    cancel_reason         VARCHAR(200),
    resolution_note       VARCHAR(500),

    triggered_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_at           TIMESTAMPTZ,
    arrived_at            TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_incidents_user ON incidents (user_id, triggered_at DESC);
CREATE INDEX ix_incidents_status ON incidents (status, triggered_at DESC);
CREATE INDEX ix_incidents_responder ON incidents (assigned_responder_id, triggered_at DESC);

-- A frightened person presses the button more than once. Without this, one emergency becomes
-- three incidents and the responders split up.
CREATE UNIQUE INDEX ux_incidents_one_active_per_user
    ON incidents (user_id)
    WHERE status IN ('TRIGGERED', 'ACCEPTED', 'RESPONDING', 'ARRIVED');

-- ═══════════════════════════════════════════════════════════════════════════════
-- Location trail
-- ═══════════════════════════════════════════════════════════════════════════════
-- Location history exists only for the duration of an incident. Nothing is written here while
-- a user is simply going about their day.
CREATE TABLE location_updates (
    id          VARCHAR(21)      PRIMARY KEY,
    incident_id VARCHAR(21)      NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    accuracy    DOUBLE PRECISION,
    -- Reported by the device, so a fix captured while offline keeps its real time once it syncs.
    recorded_at TIMESTAMPTZ      NOT NULL,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX ix_location_updates_incident ON location_updates (incident_id, recorded_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Incident timeline
-- ═══════════════════════════════════════════════════════════════════════════════
-- Append-only. This is both the audit trail and what the app renders as "what happened".
CREATE TABLE incident_events (
    id          VARCHAR(21)  PRIMARY KEY,
    incident_id VARCHAR(21)  NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    event_type  VARCHAR(40)  NOT NULL,
    actor_id    VARCHAR(21),
    actor_label VARCHAR(120),
    notes       VARCHAR(500),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_incident_events_incident ON incident_events (incident_id, occurred_at);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Notifications
-- ═══════════════════════════════════════════════════════════════════════════════
-- One row per recipient per delivery attempt. recipient_user_id is null when the contact is not
-- a registered user and only the SMS fallback applies.
CREATE TABLE notifications (
    id                VARCHAR(21)  PRIMARY KEY,
    incident_id       VARCHAR(21)  REFERENCES incidents (id) ON DELETE CASCADE,
    recipient_user_id VARCHAR(21)  REFERENCES users (id) ON DELETE CASCADE,
    recipient_phone   VARCHAR(20),
    recipient_label   VARCHAR(120),
    type              VARCHAR(40)  NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    delivery_status   VARCHAR(20)  NOT NULL,
    title             VARCHAR(160) NOT NULL,
    body              VARCHAR(600) NOT NULL,
    failure_reason    VARCHAR(200),
    acknowledged_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_notifications_recipient ON notifications (recipient_user_id, created_at DESC);
CREATE INDEX ix_notifications_incident ON notifications (incident_id, created_at);
CREATE INDEX ix_notifications_phone ON notifications (recipient_phone, created_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Families and plans
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE families (
    id            VARCHAR(21)  PRIMARY KEY,
    owner_user_id VARCHAR(21)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_families_owner ON families (owner_user_id);

CREATE TABLE family_members (
    id           VARCHAR(21)  PRIMARY KEY,
    family_id    VARCHAR(21)  NOT NULL REFERENCES families (id) ON DELETE CASCADE,
    user_id      VARCHAR(21)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    relationship VARCHAR(60),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_family_members ON family_members (family_id, user_id);
CREATE INDEX ix_family_members_user ON family_members (user_id);

-- Subscription state is simulated in the prototype: there is no billing integration, and
-- nothing in the emergency path consults it. Entitlement is modelled so the commercial shape
-- is visible without pretending payments work.
CREATE TABLE subscriptions (
    id            VARCHAR(21)  PRIMARY KEY,
    owner_user_id VARCHAR(21)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id     VARCHAR(21)  REFERENCES families (id) ON DELETE SET NULL,
    plan_type     VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    start_date    DATE         NOT NULL,
    end_date      DATE,
    simulated     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_subscriptions_owner ON subscriptions (owner_user_id);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Audit
-- ═══════════════════════════════════════════════════════════════════════════════
-- Who looked at whose location, and who changed what. Location access is the sensitive
-- capability in this system, so reads of it are audited alongside writes.
CREATE TABLE audit_log (
    id          VARCHAR(21)  PRIMARY KEY,
    actor_id    VARCHAR(21),
    action      VARCHAR(60)  NOT NULL,
    target_type VARCHAR(40),
    target_id   VARCHAR(21),
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_log_actor ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_log_target ON audit_log (target_type, target_id, occurred_at DESC);
