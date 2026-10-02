package com.sportday.service;

import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds realistic Hong Kong school register data for testing and demonstrations.
 *
 * <p>The generated population is deliberately shaped so that every grade band,
 * sex and house is populated and so that no class register breaks the
 * "class number is unique within a class" rule:</p>
 *
 * <ul>
 *   <li>Classes are grouped into forms of four ({@code 1A..1D} … {@code 6A..6D}),
 *       so the default 600 students land in 24 classes.</li>
 *   <li>Grade C (age 12–14) takes the youngest forms, grade B (15–16) the next,
 *       grade A (17+) the oldest. No class ever mixes two grade bands.</li>
 *   <li>Default 600 yields 240 / 198 / 162 across C / B / A and a maximum class
 *       size of 30, comfortably inside the usual register size of 35.</li>
 *   <li>The number of forms grows with the population, so larger registers stay
 *       legal instead of overfilling a class.</li>
 * </ul>
 *
 * <p>Output is deterministic for a given student count and reference date, so the
 * same test data can be regenerated on demand.</p>
 */
@Component
public class StudentSampleDataGenerator {

    /** Deterministic seed — regenerating the same request yields the same data. */
    private static final long SEED = 20261001L;

    /** Fraction of the population in each band; the leftover goes to grade C. */
    private static final double SHARE_C = 0.40;
    private static final double SHARE_B = 0.33;

    /** Largest class register the generator will produce. */
    public static final int MAX_CLASS_SIZE = 35;

    /** Classes per form. */
    private static final char[] CLASS_LETTERS = {'A', 'B', 'C', 'D'};

    private static final String[] SURNAMES = {
            "陳", "李", "黃", "張", "劉", "何", "梁", "吳", "林", "周",
            "鄭", "王", "馮", "蔡", "徐", "許", "鄧", "馬", "曾", "彭",
            "葉", "鍾", "江", "蘇", "呂", "盧", "譚", "沈", "趙", "楊",
            "朱", "孫", "胡", "郭", "廖", "羅", "高", "潘", "余", "唐"
    };

    private static final String[] GIVEN_CHARS = {
            "志", "偉", "文", "俊", "家", "嘉", "雅", "詠", "詩", "敏",
            "慧", "美", "麗", "小", "大", "天", "浩", "宇", "欣", "怡",
            "穎", "傑", "明", "華", "國", "建", "凱", "婷", "珊", "玲",
            "強", "威", "峰", "波", "豪", "恩", "慈", "樂", "晴", "軒",
            "霖", "楠", "彤", "柔", "朗", "嵐", "澄", "晉", "睿", "瑋",
            "泓", "峰", "謙", "逸", "希", "芯", "翹", "政", "信", "宏"
    };

    /** Houses used by the sample data. */
    public static final String[] HOUSES = {"Red", "Blue", "Green", "Yellow"};

    /** One generated student record, before it becomes entities. */
    public record StudentSpec(
            String studentId,
            String name,
            LocalDate dob,
            Sex sex,
            String className,
            Integer classNumber,
            String house) {
    }

    public List<StudentSpec> generate(int count, LocalDate referenceDate) {
        return generate(count, referenceDate, SEED);
    }

    public List<StudentSpec> generate(int count, LocalDate referenceDate, long seed) {
        if (count <= 0) {
            throw new IllegalArgumentException("student count must be positive");
        }
        LocalDate on = referenceDate != null ? referenceDate : LocalDate.now();
        Random random = new Random(seed);

        int countC = (int) Math.round(count * SHARE_C);
        int countB = (int) Math.round(count * SHARE_B);
        int countA = count - countC - countB;
        if (countA < 0) {
            countA = 0;
        }

        // Every student gets a (grade, class) slot, then we hand out class
        // numbers 1..N inside each class so they never collide. Grade C takes the
        // youngest forms, then B, then A, so no class ever holds two bands. The
        // number of forms follows the population, which keeps every register
        // within MAX_CLASS_SIZE and reproduces 24 classes at the default 600.
        int formsC = formsNeeded(countC);
        int formsB = formsNeeded(countB);
        int formsA = formsNeeded(countA);

        List<StudentSpec> specs = new ArrayList<>(count);
        int nextId = 1;
        int form = 1;

        nextId = fillBand(specs, Grade.C, countC, on, random, nextId, form, formsC);
        form += formsC;
        nextId = fillBand(specs, Grade.B, countB, on, random, nextId, form, formsB);
        form += formsB;
        fillBand(specs, Grade.A, countA, on, random, nextId, form, formsA);

        // Spread the sexes and houses evenly, then shuffle so the file is not
        // grouped by any attribute.
        List<Sex> sexes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sexes.add(i % 2 == 0 ? Sex.MALE : Sex.FEMALE);
        }
        Collections.shuffle(sexes, random);

        List<String> houses = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            houses.add(HOUSES[i % HOUSES.length]);
        }
        Collections.shuffle(houses, random);

        Set<String> names = new LinkedHashSet<>();
        List<StudentSpec> result = new ArrayList<>(count);
        for (int i = 0; i < specs.size(); i++) {
            StudentSpec base = specs.get(i);
            result.add(new StudentSpec(
                    base.studentId(),
                    uniqueName(names, random),
                    base.dob(),
                    sexes.get(i),
                    base.className(),
                    base.classNumber(),
                    houses.get(i)));
        }
        return result;
    }

    private int fillBand(List<StudentSpec> specs, Grade grade, int bandCount,
                         LocalDate referenceDate, Random random, int nextId,
                         int firstForm, int formCount) {
        if (bandCount <= 0 || formCount <= 0) {
            return nextId;
        }
        List<String> classes = classesFor(firstForm, formCount);
        int[] sizes = distribute(bandCount, classes.size());

        // Build the slots class by class, numbering each class's register 1..N.
        record Slot(String className, int classNumber) {
        }
        List<Slot> slots = new ArrayList<>(bandCount);
        for (int c = 0; c < classes.size(); c++) {
            for (int n = 1; n <= sizes[c]; n++) {
                slots.add(new Slot(classes.get(c), n));
            }
        }
        Collections.shuffle(slots, random);

        for (Slot slot : slots) {
            specs.add(new StudentSpec(
                    String.format("S%04d", nextId++),
                    null,
                    dobForAge(randomAgeInBand(grade, random), referenceDate, random),
                    null,
                    slot.className(),
                    slot.classNumber(),
                    null));
        }
        return nextId;
    }

    /** Splits {@code total} as evenly as possible across {@code buckets}. */
    static int[] distribute(int total, int buckets) {
        int[] sizes = new int[buckets];
        int base = total / buckets;
        int remainder = total % buckets;
        for (int i = 0; i < buckets; i++) {
            sizes[i] = base + (i < remainder ? 1 : 0);
        }
        return sizes;
    }

    /** How many classes are needed to hold {@code bandCount} students legally. */
    static int formsNeeded(int bandCount) {
        if (bandCount <= 0) {
            return 0;
        }
        int classesNeeded = (int) Math.ceil(bandCount / (double) MAX_CLASS_SIZE);
        int classesPerForm = CLASS_LETTERS.length;
        return (int) Math.ceil(classesNeeded / (double) classesPerForm);
    }

    /**
     * The class names for {@code formCount} forms starting at {@code firstForm},
     * e.g. firstForm=3, formCount=2 -> 3A 3B 3C 3D 4A 4B 4C 4D.
     */
    private static List<String> classesFor(int firstForm, int formCount) {
        List<String> classes = new ArrayList<>(formCount * CLASS_LETTERS.length);
        for (int f = 0; f < formCount; f++) {
            for (char letter : CLASS_LETTERS) {
                classes.add((firstForm + f) + String.valueOf(letter));
            }
        }
        return classes;
    }

    private int randomAgeInBand(Grade grade, Random random) {
        return switch (grade) {
            case C -> 12 + random.nextInt(3);   // 12, 13, 14
            case B -> 15 + random.nextInt(2);   // 15, 16
            case A -> 17 + random.nextInt(2);   // 17, 18
        };
    }

    /**
     * A date of birth that makes the student exactly {@code age} years old on
     * {@code referenceDate}: anywhere in the year-long window ending on the
     * anniversary of that birthday.
     */
    static LocalDate dobForAge(int age, LocalDate referenceDate, Random random) {
        LocalDate latest = referenceDate.minusYears(age);
        return latest.minusDays(random.nextInt(365));
    }

    private String uniqueName(Set<String> used, Random random) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String surname = SURNAMES[random.nextInt(SURNAMES.length)];
            int givenCount = random.nextInt(10) < 6 ? 2 : 1;
            StringBuilder given = new StringBuilder();
            for (int i = 0; i < givenCount; i++) {
                given.append(GIVEN_CHARS[random.nextInt(GIVEN_CHARS.length)]);
            }
            String candidate = surname + given;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        // Extremely unlikely; fall back to a guaranteed-unique suffix.
        int n = used.size() + 1;
        String candidate = SURNAMES[random.nextInt(SURNAMES.length)] + "學" + n;
        used.add(candidate);
        return candidate;
    }
}
