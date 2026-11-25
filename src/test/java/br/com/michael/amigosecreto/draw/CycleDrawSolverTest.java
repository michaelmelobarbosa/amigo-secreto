package br.com.michael.amigosecreto.draw;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleDrawSolverTest {

    private static UUID id(int value) {
        return new UUID(0, value);
    }

    private static Set<UUID> group(UUID... members) {
        return Set.of(members);
    }

    private static Map<UUID, Set<UUID>> forbiddenFrom(List<Set<UUID>> groups) {
        Map<UUID, Set<UUID>> forbidden = new HashMap<>();
        for (Set<UUID> group : groups) {
            for (UUID a : group) {
                for (UUID b : group) {
                    if (!a.equals(b)) {
                        forbidden.computeIfAbsent(a, key -> new HashSet<>()).add(b);
                    }
                }
            }
        }
        return forbidden;
    }

    private static CycleDrawSolver solver(long seed) {
        return new CycleDrawSolver(new Random(seed));
    }

    private static List<UUID> people(int count) {
        List<UUID> people = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            people.add(id(i));
        }
        return people;
    }

    private static void assertValidDraw(
            List<UUID> participants,
            Map<UUID, Set<UUID>> forbidden,
            Map<UUID, UUID> assignment) {

        assertEquals(participants.size(), assignment.size());
        assertEquals(new HashSet<>(participants), assignment.keySet());
        assertEquals(new HashSet<>(participants), new HashSet<>(assignment.values()));

        for (UUID participant : participants) {
            UUID receiver = assignment.get(participant);
            assertNotNull(receiver);
            assertNotEquals(participant, receiver, "ninguém pode tirar a si mesmo");
            assertFalse(
                    forbidden.getOrDefault(participant, Set.of()).contains(receiver)
                            || forbidden.getOrDefault(receiver, Set.of()).contains(participant),
                    "par proibido sorteado: " + participant + " -> " + receiver);
        }

        Set<UUID> visited = new HashSet<>();
        UUID current = participants.get(0);
        for (int i = 0; i < participants.size(); i++) {
            assertTrue(visited.add(current), "o ciclo não pode repetir participante");
            current = assignment.get(current);
        }
        assertEquals(participants.get(0), current, "o resultado precisa formar um único ciclo");
    }

    @Test
    void rejectsFewerThanThreeParticipants() {
        DrawImpossibleException error = assertThrows(
                DrawImpossibleException.class,
                () -> solver(1).draw(people(2), Map.of()));

        assertEquals(DrawImpossibleException.Reason.TOO_FEW_PARTICIPANTS, error.getReason());
    }

    @Test
    void drawsThreeParticipantsWithoutConstraints() {
        List<UUID> participants = people(3);

        Map<UUID, UUID> assignment = solver(1).draw(participants, Map.of());

        assertValidDraw(participants, Map.of(), assignment);
    }

    @Test
    void rejectsThreeParticipantsWhenAnyPairIsForbidden() {
        List<UUID> participants = people(3);
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(group(id(1), id(2))));

        DrawImpossibleException error = assertThrows(
                DrawImpossibleException.class,
                () -> solver(1).draw(participants, forbidden));

        assertEquals(
                DrawImpossibleException.Reason.FORBIDDEN_PAIR_WITH_THREE_PARTICIPANTS,
                error.getReason());
    }

    @Test
    void drawsFourParticipantsWithOneCouple() {
        List<UUID> participants = people(4);
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(group(id(1), id(2))));

        for (int seed = 0; seed < 20; seed++) {
            assertValidDraw(participants, forbidden, solver(seed).draw(participants, forbidden));
        }
    }

    @Test
    void drawsTwoCouplesAlternating() {
        List<UUID> participants = people(4);
        Set<UUID> firstCouple = group(id(1), id(2));
        Set<UUID> secondCouple = group(id(3), id(4));
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(firstCouple, secondCouple));

        for (int seed = 0; seed < 50; seed++) {
            Map<UUID, UUID> assignment = solver(seed).draw(participants, forbidden);
            assertValidDraw(participants, forbidden, assignment);

            for (UUID member : firstCouple) {
                assertTrue(secondCouple.contains(assignment.get(member)));
            }
            for (UUID member : secondCouple) {
                assertTrue(firstCouple.contains(assignment.get(member)));
            }
        }
    }

    @Test
    void drawsSixteenParticipantsInTwoGroupsOfEight() {
        List<UUID> participants = people(16);
        Set<UUID> firstGroup = new HashSet<>(participants.subList(0, 8));
        Set<UUID> secondGroup = new HashSet<>(participants.subList(8, 16));
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(firstGroup, secondGroup));

        for (int seed = 0; seed < 50; seed++) {
            Map<UUID, UUID> assignment = solver(seed).draw(participants, forbidden);
            assertValidDraw(participants, forbidden, assignment);

            for (UUID member : firstGroup) {
                assertTrue(secondGroup.contains(assignment.get(member)));
            }
            for (UUID member : secondGroup) {
                assertTrue(firstGroup.contains(assignment.get(member)));
            }
        }
    }

    @Test
    void detectsImpossibleBowtie() {
        UUID a = id(1);
        UUID b = id(2);
        UUID c = id(3);
        UUID d = id(4);
        UUID e = id(5);
        List<UUID> participants = List.of(a, b, c, d, e);
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(
                group(a, c),
                group(a, d),
                group(b, c),
                group(b, d)));

        DrawImpossibleException error = assertThrows(
                DrawImpossibleException.class,
                () -> solver(1).draw(participants, forbidden));

        assertEquals(DrawImpossibleException.Reason.NO_SOLUTION, error.getReason());
    }

    @Test
    void detectsThreeMutuallyExcludedParticipantsWithOneOutsider() {
        List<UUID> participants = people(4);
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(group(id(1), id(2), id(3))));

        DrawImpossibleException error = assertThrows(
                DrawImpossibleException.class,
                () -> solver(1).draw(participants, forbidden));

        assertEquals(DrawImpossibleException.Reason.NO_SOLUTION, error.getReason());
    }

    @Test
    void sameSeedProducesSameDraw() {
        List<UUID> participants = people(6);
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(
                group(id(1), id(2)),
                group(id(3), id(4), id(5))));

        Map<UUID, UUID> first = new CycleDrawSolver(new Random(42)).draw(participants, forbidden);
        Map<UUID, UUID> second = new CycleDrawSolver(new Random(42)).draw(participants, forbidden);

        assertEquals(first, second);
    }

    @Test
    void reportsSearchLimitWhenBudgetIsExhausted() {
        UUID a = id(1);
        UUID b = id(2);
        UUID c = id(3);
        UUID d = id(4);
        UUID e = id(5);
        List<UUID> participants = List.of(a, b, c, d, e);
        Map<UUID, Set<UUID>> forbidden = forbiddenFrom(List.of(
                group(a, c),
                group(a, d),
                group(b, c),
                group(b, d)));

        DrawImpossibleException error = assertThrows(
                DrawImpossibleException.class,
                () -> new CycleDrawSolver(new Random(1), 1).draw(participants, forbidden));

        assertEquals(DrawImpossibleException.Reason.SEARCH_LIMIT_EXCEEDED, error.getReason());
    }

    @Test
    void abortsWhenTimeBudgetExpires() {
        List<UUID> participants = people(10);
        CycleDrawSolver solver = new CycleDrawSolver(
                new Random(1), CycleDrawSolver.DEFAULT_STEP_BUDGET, java.time.Duration.ofNanos(1));

        DrawImpossibleException error = assertThrows(
                DrawImpossibleException.class,
                () -> solver.draw(participants, Map.of()));

        assertEquals(DrawImpossibleException.Reason.SEARCH_LIMIT_EXCEEDED, error.getReason());
    }

    @Test
    void treatsForbiddenPairsAsSymmetric() {
        List<UUID> participants = people(4);
        Map<UUID, Set<UUID>> onlyOneDirection = Map.of(id(1), Set.of(id(2)));

        for (int seed = 0; seed < 50; seed++) {
            Map<UUID, UUID> assignment = solver(seed).draw(participants, onlyOneDirection);
            assertValidDraw(participants, onlyOneDirection, assignment);
        }
    }

    @Test
    void agreesWithBruteForceOnSmallRandomGraphs() {
        Random graphRandom = new Random(123);
        for (int size = 3; size <= 7; size++) {
            for (int iteration = 0; iteration < 100; iteration++) {
                List<UUID> participants = people(size);
                Map<UUID, Set<UUID>> forbidden = randomForbidden(participants, graphRandom, 0.35);
                boolean expected = bruteForceHasCycle(participants, forbidden);
                CycleDrawSolver solver = solver(iteration);

                if (expected) {
                    assertValidDraw(participants, forbidden, solver.draw(participants, forbidden));
                } else {
                    assertThrows(DrawImpossibleException.class,
                            () -> solver.draw(participants, forbidden));
                }
            }
        }
    }

    private static Map<UUID, Set<UUID>> randomForbidden(
            List<UUID> participants, Random random, double probability) {

        Map<UUID, Set<UUID>> forbidden = new HashMap<>();
        for (int i = 0; i < participants.size(); i++) {
            for (int j = i + 1; j < participants.size(); j++) {
                if (random.nextDouble() < probability) {
                    UUID a = participants.get(i);
                    UUID b = participants.get(j);
                    forbidden.computeIfAbsent(a, key -> new HashSet<>()).add(b);
                    forbidden.computeIfAbsent(b, key -> new HashSet<>()).add(a);
                }
            }
        }
        return forbidden;
    }

    private static boolean bruteForceHasCycle(
            List<UUID> participants, Map<UUID, Set<UUID>> forbidden) {

        List<UUID> remaining = new ArrayList<>(participants.subList(1, participants.size()));
        return bruteForceSearch(participants.get(0), participants.get(0), remaining, forbidden);
    }

    private static boolean bruteForceSearch(
            UUID first,
            UUID current,
            List<UUID> remaining,
            Map<UUID, Set<UUID>> forbidden) {

        if (remaining.isEmpty()) {
            return !isForbidden(current, first, forbidden);
        }

        for (int i = 0; i < remaining.size(); i++) {
            UUID candidate = remaining.get(i);
            if (isForbidden(current, candidate, forbidden)) {
                continue;
            }

            remaining.remove(i);
            if (bruteForceSearch(first, candidate, remaining, forbidden)) {
                return true;
            }
            remaining.add(i, candidate);
        }
        return false;
    }

    private static boolean isForbidden(UUID a, UUID b, Map<UUID, Set<UUID>> forbidden) {
        return forbidden.getOrDefault(a, Set.of()).contains(b)
                || forbidden.getOrDefault(b, Set.of()).contains(a);
    }
}
