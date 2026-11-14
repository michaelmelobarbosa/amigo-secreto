package br.com.michael.amigosecreto.draw;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventStatus;
import br.com.michael.amigosecreto.exclusion.ExclusionGroup;
import br.com.michael.amigosecreto.exclusion.ExclusionGroupRepository;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executa, refaz e reabre o sorteio.
 *
 * <p>O sorteio é tudo-ou-nada: pares proibidos vêm dos grupos, a viabilidade é
 * decidida pelo {@link CycleDrawSolver} e a gravação dos {@link Assignment} e a
 * mudança de estado do evento acontecem na mesma transação. Se o solver não
 * achar solução, nada é persistido e o estado anterior continua valendo. O
 * {@link DrawCompletedEvent} só sai depois do commit, para a fase 2 notificar
 * sem ver um resultado que pode ser desfeito.
 */
@Service
public class DrawService {

    private static final String EVENT_NOT_FOUND = "Evento não encontrado.";

    private final CycleDrawSolver solver;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;
    private final ExclusionGroupRepository exclusionGroupRepository;
    private final AssignmentRepository assignmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DrawService(
            CycleDrawSolver solver,
            EventRepository eventRepository,
            ParticipantRepository participantRepository,
            ExclusionGroupRepository exclusionGroupRepository,
            AssignmentRepository assignmentRepository,
            ApplicationEventPublisher eventPublisher) {
        this.solver = solver;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
        this.exclusionGroupRepository = exclusionGroupRepository;
        this.assignmentRepository = assignmentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Primeiro sorteio: só vale com o evento aberto. O solver é a única
     * autoridade sobre mínimo de participantes e viabilidade das restrições.
     */
    @Transactional
    public void drawByAdminToken(String adminToken) {
        Event event = lockByAdminToken(adminToken);
        if (!event.isOpen()) {
            throw new RuleViolationException(
                    "O sorteio já foi realizado. Use refazer ou reabrir.");
        }
        drawAndPersist(event);
    }

    /**
     * Refaz o sorteio de um evento já sorteado. Os resultados antigos são
     * apagados e a primeira visualização de todos é zerada, porque o que cada
     * um viu deixou de valer. Os links individuais continuam os mesmos.
     */
    @Transactional
    public void redrawByAdminToken(String adminToken) {
        Event event = lockByAdminToken(adminToken);
        if (event.getStatus() != EventStatus.DRAWN) {
            throw new RuleViolationException("O sorteio ainda não foi realizado.");
        }
        assignmentRepository.deleteByEventId(event.getId());
        participantRepository.resetFirstViewedAtByEventId(event.getId());
        drawAndPersist(event);
    }

    private void drawAndPersist(Event event) {
        List<Participant> participants = participantRepository.findByEvent_Id(event.getId());
        Map<UUID, Participant> byId = participants.stream()
                .collect(Collectors.toMap(Participant::getId, Function.identity()));

        List<UUID> ids = participants.stream().map(Participant::getId).toList();
        Map<UUID, UUID> result = solver.draw(ids, forbiddenPairs(event.getId()));

        List<Assignment> assignments = new ArrayList<>(result.size());
        for (Map.Entry<UUID, UUID> entry : result.entrySet()) {
            Participant giver = byId.get(entry.getKey());
            Participant receiver = byId.get(entry.getValue());
            if (giver == null || receiver == null) {
                throw new IllegalStateException(
                        "O sorteio devolveu um participante que não pertence ao evento.");
            }
            assignments.add(new Assignment(event, giver, receiver));
        }
        assignmentRepository.saveAll(assignments);

        Instant now = Instant.now();
        event.markDrawn(now);
        publishAfterCommit(new DrawCompletedEvent(event.getId(), event.getDrawCount(), now));
    }

    /**
     * Todos os pares ordenados de cada grupo viram proibições nos dois
     * sentidos. A união de vários grupos é natural: quem está em dois grupos
     * acumula as restrições dos dois.
     */
    private Map<UUID, Set<UUID>> forbiddenPairs(UUID eventId) {
        Map<UUID, Set<UUID>> forbidden = new HashMap<>();
        for (ExclusionGroup group
                : exclusionGroupRepository.findByEventIdWithMembers(eventId)) {
            List<UUID> members = group.getMembers().stream()
                    .map(Participant::getId)
                    .toList();
            for (UUID giver : members) {
                for (UUID other : members) {
                    if (!giver.equals(other)) {
                        forbidden.computeIfAbsent(giver, key -> new HashSet<>()).add(other);
                    }
                }
            }
        }
        return forbidden;
    }

    /**
     * Publica depois do commit. Sem transação ativa (chamada direta em teste,
     * por exemplo) a publicação é imediata.
     */
    private void publishAfterCommit(DrawCompletedEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            eventPublisher.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishEvent(event);
                    }
                });
    }

    private Event lockByAdminToken(String adminToken) {
        return eventRepository.findByAdminTokenForUpdate(adminToken)
                .orElseThrow(() -> new NotFoundException(EVENT_NOT_FOUND));
    }
}
