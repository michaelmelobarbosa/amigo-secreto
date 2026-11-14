package br.com.michael.amigosecreto.event;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.common.TokenGenerator;
import br.com.michael.amigosecreto.draw.AssignmentRepository;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de criação e consulta de evento por token.
 *
 * <p>O evento nasce aberto para inscrições e já traz o organizador como
 * participante, pois ele também entra no sorteio. A consulta por token é a
 * única porta de acesso ao evento, já que não existe login.
 */
@Service
public class EventService {

    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;
    private final AssignmentRepository assignmentRepository;
    private final TokenGenerator tokenGenerator;

    public EventService(
            EventRepository eventRepository,
            ParticipantRepository participantRepository,
            AssignmentRepository assignmentRepository,
            TokenGenerator tokenGenerator) {
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
        this.assignmentRepository = assignmentRepository;
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * Cria o evento com os tokens de acesso e o organizador como participante.
     *
     * <p>A gravação do evento e do organizador acontece na mesma transação:
     * não pode existir evento sem painel nem evento sem organizador.
     */
    @Transactional
    public Event create(CreateEventCommand command) {
        Event event = new Event(
                command.name(),
                command.eventDate(),
                command.suggestedValue(),
                tokenGenerator.adminToken(),
                tokenGenerator.inviteCode(),
                Instant.now());
        eventRepository.save(event);

        Participant organizer = new Participant(
                event,
                command.organizerName(),
                tokenGenerator.accessToken(),
                true);
        participantRepository.save(organizer);
        event.getParticipants().add(organizer);

        return event;
    }

    /**
     * Busca o evento pelo token de administração. Token inexistente vira
     * {@link NotFoundException}, sem revelar o valor consultado.
     */
    @Transactional(readOnly = true)
    public Event byAdminToken(String adminToken) {
        return eventRepository.findByAdminToken(adminToken)
                .orElseThrow(() -> new NotFoundException("Evento não encontrado."));
    }

    /**
     * Busca o evento pelo código de convite. Código inexistente vira
     * {@link NotFoundException}, sem revelar o valor consultado.
     */
    @Transactional(readOnly = true)
    public Event byInviteCode(String inviteCode) {
        return eventRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new NotFoundException("Evento não encontrado."));
    }

    @Transactional(readOnly = true)
    public Participant organizerOf(Event event) {
        return participantRepository.findByEvent_IdAndOrganizerTrue(event.getId())
                .orElseThrow(() -> new NotFoundException("Organizador não encontrado."));
    }

    /**
     * Abre ou fecha as inscrições pelo painel. Só vale antes do sorteio:
     * depois de {@code DRAWN} as inscrições já estão encerradas por definição.
     */
    @Transactional
    public Event setRegistrationByAdminToken(String adminToken, boolean open) {
        Event event = eventRepository.findByAdminTokenForUpdate(adminToken)
                .orElseThrow(() -> new NotFoundException("Evento não encontrado."));
        if (!event.isOpen()) {
            throw new RuleViolationException(
                    "As inscrições só podem ser alteradas antes do sorteio.");
        }
        event.setRegistrationOpen(open);
        return event;
    }

    /**
     * Troca o código de convite: o antigo deixa de funcionar e os links
     * individuais dos participantes continuam válidos.
     */
    @Transactional
    public Event regenerateInviteCodeByAdminToken(String adminToken) {
        Event event = eventRepository.findByAdminTokenForUpdate(adminToken)
                .orElseThrow(() -> new NotFoundException("Evento não encontrado."));
        event.regenerateInviteCode(tokenGenerator.inviteCode());
        return event;
    }

    /**
     * Reabre um evento já sorteado para editar participantes e grupos.
     *
     * <p>Os resultados são apagados e a primeira visualização de todos volta a
     * ser nula: o que cada um viu não vale mais. As inscrições reabrem e o
     * número de sorteios é mantido, para a tela avisar que houve refazer.
     */
    @Transactional
    public Event reopenByAdminToken(String adminToken) {
        Event event = eventRepository.findByAdminTokenForUpdate(adminToken)
                .orElseThrow(() -> new NotFoundException("Evento não encontrado."));
        if (event.getStatus() != EventStatus.DRAWN) {
            throw new RuleViolationException("O evento já está aberto para edição.");
        }
        assignmentRepository.deleteByEventId(event.getId());
        participantRepository.resetFirstViewedAtByEventId(event.getId());
        event.reopen();
        return event;
    }

    /**
     * Apaga o evento e todos os dados dependentes. O delete é em bulk para
     * evitar que o Hibernate tente resolver referências transitórias; a cascata
     * real é feita pelo banco.
     */
    @Transactional
    public void deleteByAdminToken(String adminToken) {
        Event event = eventRepository.findByAdminTokenForUpdate(adminToken)
                .orElseThrow(() -> new NotFoundException("Evento não encontrado."));
        eventRepository.deleteByIdBulk(event.getId());
    }
}
