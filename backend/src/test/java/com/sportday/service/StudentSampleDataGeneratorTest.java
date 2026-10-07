package com.sportday.service;

import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement 7: 600 students of sample test data.
 *
 * <p>The shape matters as much as the count: every grade band must be populated,
 * the class registers must be legal (class numbers unique inside a class and no
 * class larger than a real register), and the same request must always produce
 * the same file.</p>
 */
class StudentSampleDataGeneratorTest {

    private static final LocalDate SPORT_DAY = LocalDate.of(2026, 10, 1);
    private static final int COUNT = 600;

    private static List<StudentSampleDataGenerator.StudentSpec> students;

    @BeforeAll
    static void generate() {
        students = new StudentSampleDataGenerator().generate(COUNT, SPORT_DAY);
    }

    private static Grade gradeOf(StudentSampleDataGenerator.StudentSpec spec) {
        int age = Period.between(spec.dob(), SPORT_DAY).getYears();
        return Grade.fromAge(age);
    }

    @Test
    @DisplayName("exactly 600 students, with sequential ids S0001..S0600")
    void generatesSixHundredStudents() {
        assertEquals(COUNT, students.size());
        for (int i = 0; i < COUNT; i++) {
            assertEquals(String.format("S%04d", i + 1), students.get(i).studentId(),
                    "student id at index " + i);
        }
        assertEquals(COUNT, new HashSet<>(students.stream()
                .map(StudentSampleDataGenerator.StudentSpec::studentId).toList()).size());
    }

    @Test
    @DisplayName("all 600 names are distinct Traditional Chinese names")
    void namesAreDistinctAndChinese() {
        Set<String> names = new HashSet<>();
        for (StudentSampleDataGenerator.StudentSpec spec : students) {
            assertNotNull(spec.name());
            assertTrue(spec.name().matches("[\\u4e00-\\u9fff]{2,3}"),
                    "not a 2-3 character Chinese name: " + spec.name());
            assertTrue(names.add(spec.name()), "duplicate name: " + spec.name());
        }
        assertEquals(COUNT, names.size());
    }

    @Test
    @DisplayName("the grade split is 240 C / 198 B / 162 A")
    void gradeBandsArePopulated() {
        Map<Grade, Long> counts = students.stream()
                .collect(Collectors.groupingBy(StudentSampleDataGeneratorTest::gradeOf,
                        () -> new TreeMap<>(), Collectors.counting()));
        assertEquals(240L, counts.get(Grade.C), "grade C (age <= 14)");
        assertEquals(198L, counts.get(Grade.B), "grade B (age 15-16)");
        assertEquals(162L, counts.get(Grade.A), "grade A (age >= 17)");
    }

    @Test
    @DisplayName("every date of birth lands in the right band on the sport day")
    void datesOfBirthMatchTheirBand() {
        for (StudentSampleDataGenerator.StudentSpec spec : students) {
            int age = Period.between(spec.dob(), SPORT_DAY).getYears();
            assertTrue(age >= 12 && age <= 18, "implausible age " + age + " for " + spec.studentId());
            assertTrue(Period.between(spec.dob(), SPORT_DAY).getMonths() >= 0);
            assertEquals(age, Period.between(spec.dob(), SPORT_DAY).getYears());
        }
    }

    @Test
    @DisplayName("300 boys and 300 girls")
    void sexIsBalanced() {
        Map<Sex, Long> counts = students.stream()
                .collect(Collectors.groupingBy(StudentSampleDataGenerator.StudentSpec::sex,
                        Collectors.counting()));
        assertEquals(300L, counts.get(Sex.MALE));
        assertEquals(300L, counts.get(Sex.FEMALE));
    }

    @Test
    @DisplayName("150 students in each of the four houses")
    void housesAreBalanced() {
        Map<String, Long> counts = students.stream()
                .collect(Collectors.groupingBy(StudentSampleDataGenerator.StudentSpec::house,
                        Collectors.counting()));
        assertEquals(Set.of("Red", "Blue", "Green", "Yellow"), counts.keySet());
        counts.forEach((house, count) -> assertEquals(150L, count, "house " + house));
    }

    @Test
    @DisplayName("24 classes, each no larger than a real register")
    void classRegistersAreLegal() {
        Map<String, List<StudentSampleDataGenerator.StudentSpec>> byClass = students.stream()
                .collect(Collectors.groupingBy(StudentSampleDataGenerator.StudentSpec::className));

        assertEquals(24, byClass.size(), "forms 1-6 with four classes each");
        byClass.forEach((className, members) -> {
            assertTrue(members.size() <= 35,
                    className + " has " + members.size() + " students, which exceeds a register of 35");
            Set<Integer> numbers = members.stream()
                    .map(StudentSampleDataGenerator.StudentSpec::classNumber)
                    .collect(Collectors.toCollection(HashSet::new));
            assertEquals(members.size(), numbers.size(),
                    className + " has duplicate class numbers: " + numbers);
            assertTrue(numbers.stream().allMatch(n -> n >= 1 && n <= 35),
                    className + " has a class number outside 1..35: " + numbers);
        });
    }

    @Test
    @DisplayName("no class mixes two grade bands")
    void classesDoNotStraddleGradeBands() {
        Map<String, Set<Grade>> gradesByClass = students.stream()
                .collect(Collectors.groupingBy(StudentSampleDataGenerator.StudentSpec::className,
                        Collectors.mapping(StudentSampleDataGeneratorTest::gradeOf, Collectors.toSet())));
        gradesByClass.forEach((className, grades) ->
                assertEquals(1, grades.size(),
                        className + " mixes grade bands " + grades));
    }

    @Test
    @DisplayName("grade C takes forms 1-2, B forms 3-4, A forms 5-6")
    void formsFollowTheGradeBands() {
        for (StudentSampleDataGenerator.StudentSpec spec : students) {
            char form = spec.className().charAt(0);
            Grade grade = gradeOf(spec);
            switch (grade) {
                case C -> assertTrue(form == '1' || form == '2', spec.className() + " for grade C");
                case B -> assertTrue(form == '3' || form == '4', spec.className() + " for grade B");
                case A -> assertTrue(form == '5' || form == '6', spec.className() + " for grade A");
            }
        }
    }

    @Test
    @DisplayName("the same request always produces the same data")
    void generationIsDeterministic() {
        List<StudentSampleDataGenerator.StudentSpec> again =
                new StudentSampleDataGenerator().generate(COUNT, SPORT_DAY);
        assertEquals(students, again);
    }

    @Test
    @DisplayName("the class-number distribution under a class never repeats a number")
    void classNumbersStartAtOne() {
        Map<String, List<Integer>> byClass = students.stream()
                .collect(Collectors.groupingBy(StudentSampleDataGenerator.StudentSpec::className,
                        Collectors.mapping(StudentSampleDataGenerator.StudentSpec::classNumber,
                                Collectors.toList())));
        byClass.forEach((className, numbers) -> {
            List<Integer> sorted = new ArrayList<>(numbers);
            sorted.sort(Integer::compareTo);
            for (int i = 0; i < sorted.size(); i++) {
                assertEquals(i + 1, sorted.get(i), className + " register should run 1..N");
            }
        });
    }

    @Test
    @DisplayName("other student counts still produce legal registers")
    void handlesOtherSizes() {
        for (int count : new int[]{1, 7, 100, 250, 1000}) {
            List<StudentSampleDataGenerator.StudentSpec> generated =
                    new StudentSampleDataGenerator().generate(count, SPORT_DAY);
            assertEquals(count, generated.size(), "count=" + count);
            Map<String, Long> sizes = generated.stream()
                    .collect(Collectors.groupingBy(StudentSampleDataGenerator.StudentSpec::className,
                            Collectors.counting()));
            sizes.forEach((className, size) ->
                    assertTrue(size <= 35, count + " students: " + className + " has " + size));
            Set<String> names = generated.stream()
                    .map(StudentSampleDataGenerator.StudentSpec::name).collect(Collectors.toSet());
            assertEquals(count, names.size(), "names must stay unique for count=" + count);
        }
    }

    @Test
    @DisplayName("distribute() splits evenly and never loses a student")
    void distributionIsLossless() {
        assertArrayEquals(new int[]{30, 30, 30, 30, 30, 30, 30, 30},
                StudentSampleDataGenerator.distribute(240, 8));
        assertArrayEquals(new int[]{25, 25, 25, 25, 25, 25, 24, 24},
                StudentSampleDataGenerator.distribute(198, 8));
        assertArrayEquals(new int[]{21, 21, 20, 20, 20, 20, 20, 20},
                StudentSampleDataGenerator.distribute(162, 8));
        for (int total : new int[]{1, 5, 37, 600}) {
            for (int buckets : new int[]{1, 3, 8, 24}) {
                int sum = java.util.Arrays.stream(StudentSampleDataGenerator.distribute(total, buckets)).sum();
                assertEquals(total, sum, "total=" + total + " buckets=" + buckets);
            }
        }
    }
}
