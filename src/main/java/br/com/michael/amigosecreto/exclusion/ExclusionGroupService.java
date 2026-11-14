package br.com.michael.amigosecreto.exclusion;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.exclusion.dto.ExclusionGroupView;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grupos de exclusão: pessoas que nunca podem se tirar no sorteio.
 *
 * <p>Toda escrita bloqueia o evento e revalida o estado dentro da transação.
 * Um grupo só existe antes do sorteio, com pelo menos duas pessoas do próprio
 * evento; para mexer depois de sorteado, o organizador precisa reabrir.
 */
@Service
public class ExclusionGroupService {

    /** Mínimo de membros para que o grupo proíba algum par. */
    public static final int MIN_MEMBERS = 2;

    private static final String EVENT_NOT_FOUND = "Evento não encontrado.";
    private static final String GROUP_NOT_FOUND = "Grupo não encontrado.";
    private static final String TOO_FEW_MEMBERS =
            "Um grupo de exclusão precisa de pelo menos 2 participantes distintos.";
    private static final String FOREIGN_MEMBER =
            "Todos os membros do grupo precisam ser participantes deste evento.";

    private final ExclusionGroupRepository exclusionGroupRepository;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;

    public ExclusionGroupService(
            ExclusionGroupRepository exclusionGroupRepository,
            EventRepository eventRepository,
            ParticipantRepository participantRepository) {
        this.exclusionGroupRepository = exclusionGroupRepository;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
    }

    /**
     * Cria um grupo com pares proibidos rígidos. Membros de outro evento são
     * recusados mesmo que o id exista no banco, para não furar o escopo.
     */
    @Transactional
    public ExclusionGroup create(Event event, String label, Set<UUID> memberIds) {
        Event locked = lockEvent(event);
        if (!locked.isOpen()) {
            throw new RuleViolationException(
                    "Grupos de exclusão só podem ser criados antes do sorteio.");
        }

        Set<UUID> selected = memberIds(memberIds);
        Map<UUID, Participant> participants = participantRepository
                .findByEvent_Id(locked.getId())
                .stream()
                .collect(Collectors.toMap(Participant::getId, Function.identity()));
        if (!participants.keySet().containsAll(selected)) {
            throw new RuleViolationException(FOREIGN_MEMBER);
        }

        ExclusionGroup group = new ExclusionGroup(locked, normalizeLabel(label));
        selected.forEach(memberId -> group.addMember(participants.get(memberId)));
        return exclusionGroupRepository.save(group);
    }

    /**
     * Remove o grupo e seus pares proibidos. O escopo por evento impede que um
     * id de outro evento seja apagado.
     */
    @Transactional
    public void remove(Event event, UUID groupId) {
        Event locked = lockEvent(event);
        if (!locked.isOpen()) {
            throw new RuleViolationException(
                    "Grupos de exclusão só podem ser removidos antes do sorteio.");
        }
        ExclusionGroup group = exclusionGroupRepository
                .findByIdAndEvent_Id(groupId, locked.getId())
                .orElseThrow(() -> new NotFoundException(GROUP_NOT_FOUND));
        exclusionGroupRepository.delete(group);
    }

    /**
     * Projeção da seção "Restrições" do painel, com os nomes já ordenados.
     */
    @Transactional(readOnly = true)
    public List<ExclusionGroupView> viewsFor(Event event) {
        return exclusionGroupRepository.findByEventIdWithMembers(event.getId()).stream()
                .filter(group -> group.getMembers().size() >= MIN_MEMBERS)
                .map(group -> new ExclusionGroupView(
                        group.getId(),
                        group.getLabel(),
                        group.getMembers().stream()
                                .map(Participant::getName)
                                .sorted()
                                .toList()))
                .toList();
    }

    private Event lockEvent(Event event) {
        return eventRepository.findByIdForUpdate(event.getId())
                .orElseThrow(() -> new NotFoundException(EVENT_NOT_FOUND));
    }

    private static Set<UUID> memberIds(Set<UUID> memberIds) {
        if (memberIds == null || memberIds.size() < MIN_MEMBERS) {
            throw new RuleViolationException(TOO_FEW_MEMBERS);
        }
        Set<UUID> selected = new LinkedHashSet<>();
        for (UUID memberId : memberIds) {
            if (memberId == null || !selected.add(memberId)) {
                throw new RuleViolationException(TOO_FEW_MEMBERS);
            }
        }
        return selected;
    }

    private static String normalizeLabel(String label) {
        return label == null || label.isBlank() ? null : label.trim();
    }
}
