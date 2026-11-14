-- Outbox de notificações: uma linha por participante, por sorteio e por canal.
-- A gravação acontece na mesma transação do sorteio; o envio é assíncrono e
-- idempotente, então uma falha de provedor não perde nem duplica o aviso.
CREATE TABLE notification (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL,
    draw_count INTEGER NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    idempotency_key UUID NOT NULL,
    provider_message_id VARCHAR(255),
    last_error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_notification_participant FOREIGN KEY (participant_id)
        REFERENCES participant (id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_participant_draw_channel
        UNIQUE (participant_id, draw_count, channel),
    CONSTRAINT uk_notification_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_notification_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))
);

-- O worker busca pendentes por status, em ordem de criação.
CREATE INDEX idx_notification_status ON notification (status);

-- Apagar participante ou evento leva as notificações junto (FK já cobre).
CREATE INDEX idx_notification_participant ON notification (participant_id);
