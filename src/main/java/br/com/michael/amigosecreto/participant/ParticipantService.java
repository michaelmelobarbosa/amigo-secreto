package br.com.michael.amigosecreto.participant;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.common.TokenGenerator;
import br.com.michael.amigosecreto.draw.AssignmentRepository;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inscrição, remoção e wishlist dos participantes.
 *
 * <p>Toda escrita bloqueia o evento antes de decidir: o estado e o teto de 100
 * são revalidados dentro da transação, para que duas abas ou um duplo clique
 * não furem nenhum dos dois.
 */
@Service
public class ParticipantService {

    /** Teto de participantes por evento, contando o organizador. */
    public static final int MAX_PARTICIPANTS = 100;

    private static final String PARTICIPANT_NOT_FOUND = "Participante não encontrado.";
    private static final String EVENT_NOT_FOUND = "Evento não encontrado.";

    private final ParticipantRepository participantRepository;
    private final EventRepository eventRepository;
    private final AssignmentRepository assignmentRepository;
    private final TokenGenerator tokenGenerator;

    public ParticipantService(
            ParticipantRepository participantRepository,
            EventRepository eventRepository,
            AssignmentRepository assignmentRepository,
            TokenGenerator tokenGenerator) {
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
        this.assignmentRepository = assignmentRepository;
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * Busca o participante pelo token pessoal. Token desconhecido vira
     * {@link NotFoundException} sem repetir o valor consultado.
     *
     * <p>O evento vai inicializado junto porque a página do participante o lê
     * fora da transação ({@code open-in-view=false}).
     */
    @Transactional(readOnly = true)
    public Participant byAccessToken(String accessToken) {
        Participant participant = participantRepository.findByAccessToken(accessToken)
                .orElseThrow(() -> new NotFoundException(PARTICIPANT_NOT_FOUND));
        Hibernate.initialize(participant.getEvent());
        return participant;
    }

    /**
     * O organizador inclui alguém manualmente. Basta o evento estar aberto:
     * fechar as inscrições públicas não impede o painel de completar a lista.
     */
    @Transactional
    public Participant addByOrganizer(Event event, String name, String wishlist) {
        Event locked = lockEvent(event);
        if (!locked.isOpen()) {
            throw new RuleViolationException(
                    "Participantes só podem ser adicionados antes do sorteio.");
        }
        ensureParticipantLimit(locked);
        return persist(locked, name, wishlist);
    }

    /**
     * Inscrição pelo link de convite: só vale com o evento aberto e as
     * inscrições abertas. O sorteio fecha as inscrições automaticamente.
     */
    @Transactional
    public Participant joinByInvite(Event event, String name, String wishlist) {
        Event locked = lockEvent(event);
        if (!locked.canJoin()) {
            throw new RuleViolationException("As inscrições estão encerradas.");
        }
        ensureParticipantLimit(locked);
        return persist(locked, name, wishlist);
    }

    /**
     * Remove um participante do evento. O organizador não sai: apagá-lo
     * deixaria o painel e o sorteio sem dono.
     */
    @Transactional
    public void remove(Event event, UUID participantId) {
        Event locked = lockEvent(event);
        if (!locked.isOpen()) {
            throw new RuleViolationException(
                    "Participantes só podem ser removidos antes do sorteio.");
        }
        Participant participant = participantRepository
                .findByIdAndEvent_Id(participantId, locked.getId())
                .orElseThrow(() -> new NotFoundException(PARTICIPANT_NOT_FOUND));
        if (participant.isOrganizer()) {
            throw new RuleViolationException("O organizador não pode ser removido.");
        }
        participantRepository.delete(participant);
    }

    /**
     * A wishlist pode mudar sempre, inclusive depois do sorteio: quem já viu o
     * resultado passa a ver o texto atualizado.
     */
    @Transactional
    public void updateWishlist(Participant participant, String wishlist) {
        Participant current = participantRepository.findById(participant.getId())
                .orElseThrow(() -> new NotFoundException(PARTICIPANT_NOT_FOUND));
        current.setWishlist(normalize(wishlist));
        participantRepository.save(current);
    }

    /**
     * Registra a primeira abertura do resultado sem sobrescrever uma data já
     * gravada. O update condicional é atômico, então duas abas simultâneas
     * mantêm a mesma primeira visualização.
     */
    @Transactional
    public void markFirstViewed(Participant participant) {
        Instant now = Instant.now();
        int changed = participantRepository.markFirstViewedIfAbsent(
                participant.getId(), participant.getEvent().getId(), now);
        if (changed > 0) {
            participant.markViewed(now);
        }
    }

    /**
     * Projeção usada pelo painel. Mantém o controller fora do repositório.
     */
    @Transactional(readOnly = true)
    public List<ParticipantRow> rowsFor(Event event) {
        return participantRepository.findRowsByEventId(event.getId());
    }

    /**
     * Devolve quem o participante tirou, ou vazio enquanto não houver sorteio.
     * O receiver é lido dentro da transação para não estourar lazy loading na
     * borda.
     */
    @Transactional(readOnly = true)
    public Optional<ParticipantResult> resultFor(Participant participant) {
        return assignmentRepository
                .findByEvent_IdAndGiver_Id(participant.getEvent().getId(), participant.getId())
                .map(assignment -> new ParticipantResult(
                        assignment.getReceiver().getName(),
                        assignment.getReceiver().getWishlist()));
    }

    private Event lockEvent(Event event) {
        return eventRepository.findByIdForUpdate(event.getId())
                .orElseThrow(() -> new NotFoundException(EVENT_NOT_FOUND));
    }

    private void ensureParticipantLimit(Event event) {
        if (participantRepository.countByEvent_Id(event.getId()) >= MAX_PARTICIPANTS) {
            throw new RuleViolationException(
                    "O evento já atingiu o limite de " + MAX_PARTICIPANTS + " participantes.");
        }
    }

    private Participant persist(Event event, String name, String wishlist) {
        Participant participant =
                new Participant(event, name, tokenGenerator.accessToken(), false);
        participant.setWishlist(normalize(wishlist));
        return participantRepository.save(participant);
    }

    private static String normalize(String wishlist) {
        return wishlist == null || wishlist.isBlank() ? null : wishlist;
    }
}
