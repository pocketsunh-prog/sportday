package com.sportday.entity;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.RelayTeamMemberDTO;
import com.sportday.dto.StudentDTO;
import com.sportday.dto.UserDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two things every listing of a student now carries: the <strong>form</strong>
 * read off the class, and the house's <strong>short code</strong>.
 *
 * <p>Both derivations live on {@link Student} — one place, asked by the register, the
 * mark grid, the relay rosters and the marking sheets alike — so what is asserted
 * here is what all of them answer. The case that matters most is the unknown house:
 * a school may keep a house that is not one of the four, and it must not be given a
 * letter that belongs to a real one.</p>
 */
class StudentFormAndHouseCodeTest {

    // ================================================================ the house code

    @Test
    @DisplayName("each of the four houses has its own code")
    void theFourHouses() {
        assertEquals("R", Student.houseCodeOf("Red"));
        assertEquals("Y", Student.houseCodeOf("Yellow"));
        assertEquals("B", Student.houseCodeOf("Blue"));
        assertEquals("G", Student.houseCodeOf("Green"));
    }

    @Test
    @DisplayName("the house is matched case-insensitively and trimmed")
    void mixedCaseAndSpace() {
        assertEquals("R", Student.houseCodeOf("red"));
        assertEquals("R", Student.houseCodeOf(" Red "));
        assertEquals("R", Student.houseCodeOf("RED"));
        assertEquals("R", Student.houseCodeOf("\tRed\n"));
        assertEquals("Y", Student.houseCodeOf("yElLoW"));
        assertEquals("B", Student.houseCodeOf(" blue"));
        assertEquals("G", Student.houseCodeOf("GREEN "));
    }

    @Test
    @DisplayName("a house that is not one of the four has no code — never a wrong letter")
    void anUnknownHouseHasNoCode() {
        assertNull(Student.houseCodeOf("Purple"));
        assertNull(Student.houseCodeOf("Unassigned"));
        // The trap a first-letter fallback falls into: Black and Brown would both read
        // B, which is Blue's letter, and the register would show a house that does not
        // exist as one that does.
        assertNull(Student.houseCodeOf("Black"));
        assertNull(Student.houseCodeOf("Brown"));
        assertNull(Student.houseCodeOf("red house"));
        assertNull(Student.houseCodeOf(""));
        assertNull(Student.houseCodeOf("   "));
        assertNull(Student.houseCodeOf(null));
    }

    @Test
    @DisplayName("the stored house keeps its full name; only the code is derived")
    void theStoredHouseIsUntouched() {
        Student student = Student.builder().className("5A").house(" Red ").build();

        assertEquals(" Red ", student.getHouse(), "what the register holds is what it holds");
        assertEquals("R", student.getHouseCode());

        Student other = Student.builder().className("5A").house("Black").build();
        assertEquals("Black", other.getHouse());
        assertNull(other.getHouseCode());
    }

    // ==================================================================== the form

    @Test
    @DisplayName("the form is the leading digits of the class")
    void theFormOfAClass() {
        assertEquals("5", Student.formOf("5A"));
        assertEquals("1", Student.formOf(" 1B "));
        assertEquals("1", Student.formOf("01A"), "leading zeros are dropped");
        assertEquals("10", Student.formOf("10B"), "10B is Form 10, not Form 1");
        assertEquals("12", Student.formOf("12A"));
        assertEquals("6", Student.formOf("6A"));
    }

    @Test
    @DisplayName("a class that names no form has no form")
    void aClassThatNamesNoForm() {
        assertNull(Student.formOf("A1"));
        assertNull(Student.formOf("Senior"));
        assertNull(Student.formOf(""));
        assertNull(Student.formOf("   "));
        assertNull(Student.formOf(null));
    }

    @Test
    @DisplayName("a student's own form and house code are read from their record")
    void aStudentsOwnFormAndHouseCode() {
        Student student = Student.builder().className("10B").house("yellow").build();

        assertEquals("10", student.getForm());
        assertEquals("Y", student.getHouseCode());

        Student noForm = Student.builder().className("A1").house("Red").build();
        assertNull(noForm.getForm());
        assertEquals("R", noForm.getHouseCode());
    }

    // ================================================ the listings that carry them

    @Test
    @DisplayName("the register carries the form and the house code")
    void theRegisterCarriesThem() {
        StudentDTO dto = StudentDTO.from(student("5A", "Red"), LocalDate.of(2026, 10, 1));

        assertEquals("5A", dto.getClassName());
        assertEquals("5", dto.getForm());
        assertEquals("Red", dto.getHouse());
        assertEquals("R", dto.getHouseCode());
    }

    @Test
    @DisplayName("an entry — the marking sheet — carries them too")
    void anEntryCarriesThem() {
        Student roster = student("10B", "Green");
        Enrollment entry = Enrollment.builder()
                .id(7L)
                .user(roster.getUser())
                .status(Enrollment.EnrollmentStatus.CONFIRMED)
                .build();

        EnrollmentDTO dto = EnrollmentDTO.from(entry, roster);

        assertEquals("10B", dto.getClassName());
        assertEquals("10", dto.getForm());
        assertEquals("Green", dto.getHouse());
        assertEquals("G", dto.getHouseCode());
    }

    @Test
    @DisplayName("a relay roster carries them too, and agrees with the register")
    void aRelayRosterCarriesThem() {
        Student roster = student("2C", " blue ");
        Event event = Event.builder().id(1L).name("4x100M").build();
        RelayTeam team = RelayTeam.builder().id(2L).event(event).kind(RelayTeamKind.FORM)
                .teamKey("2C").label("2C").build();
        RelayTeamMember member = RelayTeamMember.builder().id(3L).team(team)
                .user(roster.getUser()).leg(1).build();

        RelayTeamMemberDTO dto = RelayTeamMemberDTO.from(member, roster, 4);
        StudentDTO register = StudentDTO.from(roster, LocalDate.of(2026, 10, 1));

        assertEquals("2C", dto.getClassName());
        assertEquals("2", dto.getForm());
        assertEquals(" blue ", dto.getHouse());
        assertEquals("B", dto.getHouseCode());
        assertEquals(register.getForm(), dto.getForm(),
                "the roster and the register are one derivation, so they cannot disagree");
        assertEquals(register.getHouseCode(), dto.getHouseCode());
    }

    @Test
    @DisplayName("a student's own account carries form, class and house")
    void theStudentInfoCarriesThem() {
        Student roster = student("1A", "Yellow");

        UserDTO dto = UserDTO.from(roster.getUser(), roster);

        assertEquals("1A", dto.getClassName());
        assertEquals("1", dto.getForm());
        assertEquals("Yellow", dto.getHouse());
        assertEquals("Y", dto.getHouseCode());
    }

    @Test
    @DisplayName("an account with no register row carries no form and no house code")
    void aStaffAccountHasNeither() {
        User staff = User.builder().id(99L).username("office").fullName("Office")
                .role(User.Role.ADMIN).enabled(true).build();

        UserDTO dto = UserDTO.from(staff);

        assertNull(dto.getForm());
        assertNull(dto.getHouseCode());
        assertNull(dto.getClassName());
    }

    // ==================================================================== fixtures

    private static Student student(String className, String house) {
        User account = User.builder().id(11L).username("S0011").fullName("Chan Tai Man")
                .role(User.Role.STUDENT).enabled(true).build();
        return Student.builder()
                .id(11L)
                .user(account)
                .studentId("S0011")
                .name("Chan Tai Man")
                .dob(LocalDate.of(2011, 5, 5))
                .sex(Sex.MALE)
                .className(className)
                .classNumber(1)
                .house(house)
                .grade(Grade.B)
                .enabled(true)
                .build();
    }
}
