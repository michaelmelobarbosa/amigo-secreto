-- E-mail opcional do participante. O convite continua funcionando por link;
-- o e-mail só serve para avisar quando o sorteio acontece.
ALTER TABLE participant ADD COLUMN email VARCHAR(254);

-- Busca de e-mails de um mesmo evento (reenvio, diagnóstico e eventual
-- deduplicação) sem varrer a tabela inteira.
CREATE INDEX idx_participant_event_email ON participant (event_id, email);
