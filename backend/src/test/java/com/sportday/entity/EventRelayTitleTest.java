package com.sportday.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <strong>A relay is named by the scope its own kind is divided by</strong>, whatever
 * scope its stored name happens to carry.
 *
 * <p>The live programme holds both shapes, and the school hit the fault in print: the
 * Form 3 relays are stored as {@code Boys 4x100M Relay · B Grade} — and
 * {@code 6050}, {@code 6051}, {@code 6054}–{@code 6059} are exactly that — so a marking
 * sheet headed by the stored name told a helper the race was a B Grade one when its
 * teams are {@code 3A} to {@code 3D}. On a form relay the grade is decorative: a Form 3
 * relay takes whoever is in Form 3 whatever grade they are in.</p>
 *
 * <p>This is the one rule, on the event, that the event list, the group the marking
 * sheet is drawn from and the sheet itself all ask
 * ({@link Event#getRelayTitle()}). Nothing here rewrites the stored name — the tests
 * assert that it is still what the school typed afterwards.</p>
 */
class EventRelayTitleTest {

    private static Event relay(String name, RelayTeamKind kind, Grade grade, String form) {
        return Event.builder()
                .id(1L)
                .name(name)
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.RELAY)
                .sex(Sex.MALE)
                .grade(grade)
                .form(form)
                .relayTeamKind(kind)
                .eventDate(LocalDate.of(2026, 10, 4))
                .groupSize(24)
                .enabled(true)
                .build();
    }

    private static Event sprint(String name) {
        return Event.builder()
                .id(2L)
                .name(name)
                .type(Event.EventType.RUN_100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 4))
                .groupSize(8)
                .enabled(true)
                .build();
    }

    // ------------------------------------------------------- a FORM relay

    @Test
    @DisplayName("a form relay stored under a grade is named by its form, and not by that grade")
    void aFormRelayStoredUnderAGradeIsNamedByItsForm() {
        // The live shape: event 6050 is the Form 3 relay, stored as "· B Grade".
        Event relay = relay("Boys 4x100M Relay · B Grade", RelayTeamKind.FORM, Grade.B, "3");

        assertEquals("Boys 4x100M Relay · Form 3", relay.getRelayTitle(),
                "the form decides who runs, so the form is what the relay is called");
        assertEquals("Boys 4x100M Relay · B Grade", relay.getName(),
                "and the stored name — live data — is not rewritten by asking");
    }

    @Test
    @DisplayName("the live grade-sounding form relays all come out as their own forms")
    void theLiveGradeSoundingFormRelaysComeOutAsTheirForms() {
        record Live(String name, Grade grade, String form, String title) {
        }
        List<Live> live = List.of(
                // 6050 / 6051 — the Form 3 relays.
                new Live("Boys 4x100M Relay · B Grade", Grade.B, "3", "Boys 4x100M Relay · Form 3"),
                new Live("Girls 4x100M Relay · B Grade", Grade.B, "3", "Girls 4x100M Relay · Form 3"),
                // 6052 / 6053 — the Form 4 relays.
                new Live("Boys 4x100M Relay · B Grade", Grade.B, "4", "Boys 4x100M Relay · Form 4"),
                new Live("Girls 4x100M Relay · B Grade", Grade.B, "4", "Girls 4x100M Relay · Form 4"),
                // 6054 / 6055 — the Form 6 relays.
                new Live("Boys 4x100M Relay · A Grade", Grade.A, "6", "Boys 4x100M Relay · Form 6"),
                new Live("Girls 4x100M Relay · A Grade", Grade.A, "6", "Girls 4x100M Relay · Form 6"),
                // 6056 / 6057 — the Form 1 relays, 6058 / 6059 — the Form 2 ones.
                new Live("Boys 4x400M Relay · C Grade", Grade.C, "1", "Boys 4x400M Relay · Form 1"),
                new Live("Girls 4x400M Relay · C Grade", Grade.C, "1", "Girls 4x400M Relay · Form 1"),
                new Live("Boys 4x400M Relay · C Grade", Grade.C, "2", "Boys 4x400M Relay · Form 2"),
                new Live("Girls 4x400M Relay · C Grade", Grade.C, "2", "Girls 4x400M Relay · Form 2"),
                // 6060 / 6061 — the Form 5 relays.
                new Live("Boys 4x400M Relay · A Grade", Grade.A, "5", "Boys 4x400M Relay · Form 5"),
                new Live("Girls 4x400M Relay · A Grade", Grade.A, "5", "Girls 4x400M Relay · Form 5"));

        for (Live row : live) {
            Event relay = relay(row.name(), RelayTeamKind.FORM, row.grade(), row.form());
            assertEquals(row.title(), relay.getRelayTitle(), row.name());
            assertEquals(row.name(), relay.getName(), "the stored name is untouched: " + row.name());
        }
    }

    @Test
    @DisplayName("a name that already says its own form is kept, word for word and separator and all")
    void aNameThatAlreadySaysItsFormIsKept() {
        for (String name : List.of(
                "Boys 4x100M Relay - Form 1",
                "Boys 4x100M Relay - Form 5",
                "Girls 4x400M Relay · Form 3",
                "Girls 4x400M Relay - Form 6")) {
            String form = name.substring(name.lastIndexOf("Form") + 4).trim();
            Event relay = relay(name, RelayTeamKind.FORM, Grade.B, form);

            assertEquals(name, relay.getRelayTitle(),
                    "Form N never becomes Form N · Form N: " + name);
        }
    }

    @Test
    @DisplayName("a name that says the form anywhere in the line is the school's own line, and is kept")
    void aNameThatSaysTheFormAnywhereIsKept() {
        Event relay = relay("Form 3 Boys 4x100M Relay", RelayTeamKind.FORM, Grade.C, "3");

        assertEquals("Form 3 Boys 4x100M Relay", relay.getRelayTitle());
    }

    @Test
    @DisplayName("a form relay whose stored name says a different form takes the form it is scoped to")
    void aFormRelayKeepsItsOwnFormOverAStoredOne() {
        Event relay = relay("Boys 4x100M Relay - Form 5", RelayTeamKind.FORM, Grade.B, "3");

        assertEquals("Boys 4x100M Relay · Form 3", relay.getRelayTitle());
    }

    @Test
    @DisplayName("a form relay with no form set keeps its name: there is no scope to name it by")
    void aFormRelayWithNoFormKeepsItsName() {
        Event relay = relay("Boys 4x100M Relay · B Grade", RelayTeamKind.FORM, Grade.B, null);

        assertEquals("Boys 4x100M Relay · B Grade", relay.getRelayTitle());
    }

    // ------------------------------------------------------ a HOUSE relay

    @Test
    @DisplayName("a house relay is named by its grade, exactly as the school wrote it")
    void aHouseRelayIsNamedByItsGrade() {
        record Live(String name, Grade grade) {
        }
        // Every house relay on the live programme, and the other order a grade is
        // written in for good measure.
        List<Live> live = List.of(
                new Live("Boys 4x100M Relay - B Grade", Grade.B),
                new Live("Girls 4x100M Relay - A Grade", Grade.A),
                new Live("Girls 4x100M Relay - C Grade", Grade.C),
                new Live("Boys 4x100M Relay · A Grade", Grade.A),
                new Live("Girls 4x100M Relay · B Grade", Grade.B),
                new Live("Boys 4x400M Relay · C Grade", Grade.C),
                new Live("Girls 4x400M Relay - Grade C", Grade.C));

        for (Live row : live) {
            Event relay = relay(row.name(), RelayTeamKind.HOUSE, row.grade(), null);
            assertEquals(row.name(), relay.getRelayTitle(),
                    "a house relay's own grade line is kept as written: " + row.name());
        }
    }

    @Test
    @DisplayName("a house relay that is stored under a form is named by its grade instead")
    void aHouseRelayStoredUnderAFormIsNamedByItsGrade() {
        Event relay = relay("Girls 4x100M Relay - Form 3", RelayTeamKind.HOUSE, Grade.C, null);

        assertEquals("Girls 4x100M Relay · C Grade", relay.getRelayTitle(),
                "the grade is what divides a house relay, so a form in its name is the fault");
        assertEquals("Girls 4x100M Relay - Form 3", relay.getName());
    }

    @Test
    @DisplayName("a house relay whose name says another grade takes the grade it is actually run in")
    void aHouseRelayWithTheWrongGradeTakesItsOwn() {
        Event relay = relay("Girls 4x100M Relay · A Grade", RelayTeamKind.HOUSE, Grade.C, null);

        assertEquals("Girls 4x100M Relay · C Grade", relay.getRelayTitle());
    }

    @Test
    @DisplayName("a house relay whose name names no scope at all is headed by its grade")
    void aHouseRelayWithNoScopeTakesItsGrade() {
        Event relay = relay("Girls 4x100M Relay", RelayTeamKind.HOUSE, Grade.C, null);

        assertEquals("Girls 4x100M Relay · C Grade", relay.getRelayTitle());
    }

    // ------------------------------------------- what this rule never touches

    @Test
    @DisplayName("an undivided relay keeps whatever it has today")
    void anUndividedRelayKeepsItsName() {
        assertEquals("Boys 4x100M Relay · B Grade",
                relay("Boys 4x100M Relay · B Grade", null, Grade.B, null).getRelayTitle());
        assertEquals("Girls 4x400M Relay - Form 3",
                relay("Girls 4x400M Relay - Form 3", null, Grade.C, null).getRelayTitle());
    }

    @Test
    @DisplayName("an individual event has no relay title at all")
    void anIndividualEventHasNoRelayTitle() {
        assertNull(sprint("Boys 100M - B Grade").getRelayTitle(),
                "a sprint is not a relay and is headed by its own name, unchanged");
        assertNull(sprint("Boys 100M - B Grade").getRelayTitle());
    }
}
