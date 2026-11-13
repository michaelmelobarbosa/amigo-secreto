package br.com.michael.amigosecreto.draw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Sorteia um único ciclo entre os participantes, respeitando os pares proibidos.
 *
 * <p>O ciclo é construído com busca em profundidade e retrocesso. Em cada passo
 * os candidatos são ordenados por menos opções restantes (MRV) e a ordem é
 * embaralhada com o {@link RandomGenerator} injetado: use {@code SecureRandom}
 * em produção e {@code Random} com semente fixa nos testes.</p>
 */
public final class CycleDrawSolver {

    public static final int DEFAULT_STEP_BUDGET = 1_000_000;

    private final RandomGenerator random;
    private final int stepBudget;

    public CycleDrawSolver(RandomGenerator random) {
        this(random, DEFAULT_STEP_BUDGET);
    }

    public CycleDrawSolver(RandomGenerator random, int stepBudget) {
        this.random = Objects.requireNonNull(random, "random");
        if (stepBudget <= 0) {
            throw new IllegalArgumentException("stepBudget deve ser positivo");
        }
        this.stepBudget = stepBudget;
    }

    /**
     * @return mapa participante -> pessoa que ele tirou, na ordem do ciclo
     */
    public Map<UUID, UUID> draw(List<UUID> participants, Map<UUID, Set<UUID>> forbidden) {
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(forbidden, "forbidden");

        List<UUID> people = List.copyOf(participants);
        if (people.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("participants não pode conter null");
        }
        if (new HashSet<>(people).size() != people.size()) {
            throw new IllegalArgumentException("participants não pode conter duplicatas");
        }

        Map<UUID, List<UUID>> allowed = buildAllowed(people, forbidden);
        validateMinimum(people, allowed);

        UUID start = mostConstrained(people, allowed);
        Set<UUID> visited = new HashSet<>();
        visited.add(start);

        Map<UUID, UUID> assignment = new LinkedHashMap<>();
        int[] steps = new int[1];

        if (!search(start, start, people, allowed, visited, assignment, steps)) {
            if (steps[0] > stepBudget) {
                throw new DrawImpossibleException(
                        DrawImpossibleException.Reason.SEARCH_LIMIT_EXCEEDED,
                        "Não foi possível encontrar um sorteio dentro do limite de busca. "
                                + "Tente novamente ou revise as restrições.");
            }
            throw new DrawImpossibleException(
                    DrawImpossibleException.Reason.NO_SOLUTION,
                    "Não existe sorteio válido com as restrições atuais.");
        }

        return Collections.unmodifiableMap(new LinkedHashMap<>(assignment));
    }

    private Map<UUID, List<UUID>> buildAllowed(List<UUID> people, Map<UUID, Set<UUID>> forbidden) {
        Map<UUID, List<UUID>> allowed = new HashMap<>(people.size());
        for (UUID person : people) {
            List<UUID> targets = new ArrayList<>(people.size() - 1);
            for (UUID other : people) {
                if (!other.equals(person) && !isForbidden(person, other, forbidden)) {
                    targets.add(other);
                }
            }
            allowed.put(person, targets);
        }
        return allowed;
    }

    private boolean isForbidden(UUID a, UUID b, Map<UUID, Set<UUID>> forbidden) {
        return forbidden.getOrDefault(a, Set.of()).contains(b)
                || forbidden.getOrDefault(b, Set.of()).contains(a);
    }

    private void validateMinimum(List<UUID> people, Map<UUID, List<UUID>> allowed) {
        int size = people.size();
        if (size < 3) {
            throw new DrawImpossibleException(
                    DrawImpossibleException.Reason.TOO_FEW_PARTICIPANTS,
                    "São necessários pelo menos 3 participantes para sortear.");
        }

        boolean hasForbiddenPair = people.stream()
                .anyMatch(person -> allowed.get(person).size() < size - 1);
        if (size == 3 && hasForbiddenPair) {
            throw new DrawImpossibleException(
                    DrawImpossibleException.Reason.FORBIDDEN_PAIR_WITH_THREE_PARTICIPANTS,
                    "Com 3 participantes, qualquer restrição impede o sorteio: "
                            + "em um ciclo de 3 pessoas todos os pares ficam frente a frente.");
        }

        for (UUID person : people) {
            if (allowed.get(person).isEmpty()) {
                throw new DrawImpossibleException(
                        DrawImpossibleException.Reason.NO_ALLOWED_TARGET,
                        "Há um participante sem ninguém que possa tirar.");
            }
        }
    }

    private UUID mostConstrained(List<UUID> people, Map<UUID, List<UUID>> allowed) {
        UUID chosen = people.get(0);
        for (UUID person : people) {
            if (allowed.get(person).size() < allowed.get(chosen).size()) {
                chosen = person;
            }
        }
        return chosen;
    }

    private boolean search(
            UUID current,
            UUID start,
            List<UUID> people,
            Map<UUID, List<UUID>> allowed,
            Set<UUID> visited,
            Map<UUID, UUID> assignment,
            int[] steps) {

        if (visited.size() == people.size()) {
            if (allowed.get(current).contains(start)) {
                assignment.put(current, start);
                return true;
            }
            return false;
        }

        List<UUID> candidates = new ArrayList<>(allowed.get(current));
        candidates.removeAll(visited);
        shuffle(candidates);
        candidates.sort(Comparator.comparingInt(candidate -> remainingOptions(candidate, visited, allowed)));

        for (UUID candidate : candidates) {
            if (++steps[0] > stepBudget) {
                return false;
            }

            assignment.put(current, candidate);
            visited.add(candidate);

            if (!hasDeadEnd(people, allowed, visited, start)
                    && search(candidate, start, people, allowed, visited, assignment, steps)) {
                return true;
            }

            visited.remove(candidate);
            assignment.remove(current);
        }

        return false;
    }

    private void shuffle(List<UUID> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            UUID value = values.get(i);
            values.set(i, values.get(j));
            values.set(j, value);
        }
    }

    private int remainingOptions(UUID candidate, Set<UUID> visited, Map<UUID, List<UUID>> allowed) {
        int count = 0;
        for (UUID target : allowed.get(candidate)) {
            if (!visited.contains(target)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Poda: todo participante ainda não visitado precisa de um próximo possível.
     * Se ele só pode apontar para o início, obrigatoriamente é o último; mais de
     * um participante nessa situação torna o ciclo impossível.
     */
    private boolean hasDeadEnd(
            List<UUID> people,
            Map<UUID, List<UUID>> allowed,
            Set<UUID> visited,
            UUID start) {

        int mustBeLast = 0;
        for (UUID person : people) {
            if (visited.contains(person)) {
                continue;
            }

            boolean hasUnvisitedTarget = false;
            for (UUID target : allowed.get(person)) {
                if (!visited.contains(target)) {
                    hasUnvisitedTarget = true;
                    break;
                }
            }

            if (!hasUnvisitedTarget) {
                if (!allowed.get(person).contains(start)) {
                    return true;
                }
                mustBeLast++;
                if (mustBeLast > 1) {
                    return true;
                }
            }
        }
        return false;
    }
}
