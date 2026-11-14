CREATE TABLE event (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    event_date DATE,
    suggested_value NUMERIC(10, 2),
    admin_token VARCHAR(64) NOT NULL,
    invite_code VARCHAR(64) NOT NULL,
    registration_open BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL,
    drawn_at TIMESTAMP WITH TIME ZONE,
    draw_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_event_admin_token UNIQUE (admin_token),
    CONSTRAINT uk_event_invite_code UNIQUE (invite_code),
    CONSTRAINT ck_event_status CHECK (status IN ('OPEN', 'DRAWN'))
);

CREATE TABLE participant (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    access_token VARCHAR(64) NOT NULL,
    wishlist VARCHAR(2000),
    organizer BOOLEAN NOT NULL,
    first_viewed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_participant_access_token UNIQUE (access_token),
    CONSTRAINT fk_participant_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
);

CREATE INDEX idx_participant_event ON participant (event_id);

CREATE TABLE exclusion_group (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    label VARCHAR(80),
    CONSTRAINT fk_exclusion_group_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
);

CREATE INDEX idx_exclusion_group_event ON exclusion_group (event_id);

CREATE TABLE exclusion_group_member (
    group_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    PRIMARY KEY (group_id, participant_id),
    CONSTRAINT fk_egm_group FOREIGN KEY (group_id) REFERENCES exclusion_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_egm_participant FOREIGN KEY (participant_id) REFERENCES participant (id) ON DELETE CASCADE
);

CREATE INDEX idx_egm_participant ON exclusion_group_member (participant_id);

CREATE TABLE assignment (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    giver_id UUID NOT NULL,
    receiver_id UUID NOT NULL,
    CONSTRAINT fk_assignment_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_giver FOREIGN KEY (giver_id) REFERENCES participant (id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_receiver FOREIGN KEY (receiver_id) REFERENCES participant (id) ON DELETE CASCADE,
    CONSTRAINT uk_assignment_event_giver UNIQUE (event_id, giver_id),
    CONSTRAINT uk_assignment_event_receiver UNIQUE (event_id, receiver_id),
    CONSTRAINT ck_assignment_not_self CHECK (giver_id <> receiver_id)
);

CREATE INDEX idx_assignment_event ON assignment (event_id);
