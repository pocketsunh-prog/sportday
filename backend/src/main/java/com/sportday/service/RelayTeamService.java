package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.dto.RelayApplicantDTO;
import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.dto.RelayTeamDerivationDTO;
import com.sportday.dto.RelayTeamMoveDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Student;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Relay teams: deriving them from the register, and deciding who may run in them.
 *
 * <h2>What a relay team is</h2>
 * <p>A relay event may be given a {@link RelayTeamKind}: {@code FORM} gives one team
 * per <strong>class</strong> — {@code 1A}, {@code 1B}, {@code 1C}, {@code 1D}, then
 * {@code 2A}, taken whole from {@link Student#getClassName()} and named after the
 * class — and {@code HOUSE} gives one team per house, named {@code C Grade Yellow}.</p>
 *
 * <p><strong>Which classes a form relay takes is the event's form</strong>
 * ({@link Event#getForm()}), not its grade: a "Form 1 4x100M" has the teams
 * {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} drawn from every class in Form 1
 * <em>whatever grade its students are in</em>, and Form 2 is a separate event. A form
 * relay with no form is scoped by its grade, exactly as every relay was before the
 * form existed, so the live events are untouched. A house relay stays grade × house:
 * because an event belongs to exactly one grade, it is one team per house present
 * among that grade's students.</p>
 *
 * <p>An event with no kind is simply <strong>undivided</strong> — it derives no teams,
 * and nothing here invents any. That is how the relay events already in the
 * programme behave, so this feature adds a choice without taking one away.</p>
 *
 * <h2>The selection rules, in one place</h2>
 * <p>Every rule a teacher could otherwise work around is enforced inside
 * {@link #requireEligibleForTeam}, so no endpoint can bypass it:</p>
 * <ol>
 *   <li>the caller may act for the student at all —
 *       {@link TeacherClassService#requireMayHelp(Student)}, which lets an
 *       administrator help anybody and a teacher only the classes assigned to
 *       them;</li>
 *   <li>the runner is on this year's list — a locked student is not;</li>
 *   <li>the runner is in the <strong>event's division and scope</strong> — its grade, or
 *       its form on a form-scoped event, which is the same rule entry uses, restated
 *       here because the original is private inside
 *       {@code EnrollmentService};</li>
 *   <li>the runner is in the <strong>one group the team runs for</strong>: the class of
 *       a form-class relay, the house of a house relay. The rule is read from the
 *       <em>event's</em> kind, so it binds a team made by hand exactly as it binds a
 *       derived one — a hand-made team carries no kind of its own, and judging it by
 *       that would exempt it from the very rule the school needs;</li>
 *   <li>the same athlete cannot hold two legs of the same event across teams at
 *       all;</li>
 *   <li>the team's size is respected — four legs for a 4x100M, and no more than one
 *       reserve past them unless the event says otherwise.</li>
 * </ol>
 *
 * <h2>The two rules that needed deciding</h2>
 * <ul>
 *   <li><strong>Both kinds of team at once — yes.</strong> An athlete may hold a leg
 *       in a class team and a leg in a house team, because those are different
 *       events: the division and the kind live on the event, so a form relay and a
 *       house relay are two races. They may not hold two legs of the <em>same</em>
 *       event.</li>
 *   <li><strong>Team size and reserves.</strong> The size of the race is the
 *       event's business ({@link Event#getEffectiveRelayTeamSize()}), and it is four
 *       for both relays the programme runs. The school's rule is four runners and
 *       <strong>one</strong> reserve, so going past the legs is refused unless the
 *       event has explicitly allowed reserves
 *       ({@link Event#isRelayReservesAllowed()}), and even then the ceiling is the
 *       legs plus {@link Event#RESERVE_ALLOWANCE}. A team short of its four is
 *       <em>not</em> refused: it is saved and reported incomplete, because the
 *       runners are collected one at a time.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelayTeamService {

    private final RelayTeamRepository relayTeamRepository;
    private final RelayTeamMemberRepository relayTeamMemberRepository;
    private final EventRepository eventRepository;
    private final StudentRepository studentRepository;
    private final TeacherClassService teacherClassService;
    private final EnrollmentRepository enrollmentRepository;

    /**
     * Classes in school order — 1A, 1B, 1D, 2A — then houses alphabetically, then the
     * teams made by hand, which belong to neither and are read last as the school's own.
     */
    private static final Comparator<RelayTeam> TEAM_ORDER = (left, right) -> {
        int byKind = Integer.compare(kindOrder(left.getKind()), kindOrder(right.getKind()));
        if (byKind != 0) {
            return byKind;
        }
        if (left.getKind() == RelayTeamKind.FORM && right.getKind() == RelayTeamKind.FORM) {
            // A form team's key is a class name, so the form has to be read off the
            // front of it: 2A comes after 1D, which a plain string compare gets right
            // only by luck and gets wrong the moment a school numbers past 9.
            int byForm = Integer.compare(classFormNumber(left.getTeamKey()),
                    classFormNumber(right.getTeamKey()));
            if (byForm != 0) {
                return byForm;
            }
        }
        return String.valueOf(left.getTeamKey()).compareToIgnoreCase(String.valueOf(right.getTeamKey()));
    };

    // ================================================================= the form

    /**
     * The form a class belongs to — {@code 1} for {@code 1A}, {@code 10} for
     * {@code 10B}, and {@code null} for a class name that names no form.
     *
     * <p>Kept as this service's own name for the relay code that reads it, but not
     * a second derivation: it is {@link Student#formOf(String)}, the one place the
     * form is read off a class name, so the relay board, the register and the mark
     * grid cannot disagree about what Form 1 is.</p>
     */
    public static String formKeyOf(String className) {
        return Student.formOf(className);
    }

    private static int classFormNumber(String teamKey) {
        return formNumber(formKeyOf(teamKey));
    }

    /**
     * The class a form team stands for: the class name itself, in the register's own
     * canonical spelling.
     *
     * <p>A form relay is divided by <strong>class</strong>, not by form — {@code 1A}
     * and {@code 1B} are two teams of the same form rather than one team between
     * them — because that is what the school enters: 1A, 1B, 1C, 1D, then 2A. The
     * team's name is the class name, so it reads on the sheet exactly as the class
     * does.</p>
     *
     * <p>The name is normalised the way the register and {@code TeacherClass} write
     * it — upper case, no spaces — so a team's key is the same string a teacher's
     * class assignment holds, and the two can be compared without either side having
     * to guess at the other's spelling.</p>
     *
     * @return the canonical class name, or {@code null} when there is none
     */
    public static String classKeyOf(String className) {
        String normalized = StudentPasswordPolicy.normalizeClass(className);
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    /**
     * The house a student is in, trimmed, or {@code null} when they have none — the
     * value a house relay's teams are matched on, and the counterpart of
     * {@link #classKeyOf} for a form-class relay.
     *
     * <p>One place, so the eligibility rule and the derive cannot disagree about what
     * a house is: a blank house is no house at all, and the same string on two rows is
     * the same house.</p>
     */
    private static String houseOf(Student student) {
        if (student == null || student.getHouse() == null) {
            return null;
        }
        String trimmed = student.getHouse().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int formNumber(String teamKey) {
        if (teamKey == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(teamKey);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private static int kindOrder(RelayTeamKind kind) {
        return kind == null ? Integer.MAX_VALUE : kind.ordinal();
    }

    // ================================================================ the board

    /**
     * The teams of an event with their runners.
     *
     * <p>A relay with no kind is undivided and derives no teams — that is a normal,
     * expected answer rather than a refusal. Something that is not a relay at all is
     * refused, because there is nothing there to describe.</p>
     */
    @Transactional(readOnly = true)
    public RelayEventTeamsDTO getBoard(Long eventId) {
        Event event = requireEvent(eventId);
        return board(event);
    }

    /** How many relay teams an event has — used before its kind is changed. */
    @Transactional(readOnly = true)
    public long countTeamsForEvent(Long eventId) {
        return relayTeamRepository.countByEventId(eventId);
    }

    private RelayEventTeamsDTO board(Event event) {
        requireRelayEvent(event, "has no relay teams");
        List<RelayTeam> teams = relayTeamRepository.findByEventIdOrderByIdAsc(event.getId());
        // Read once for the whole board: the legs of every team serve both the teams
        // above and the applicant list below, which needs to know who is already on
        // one. A query per applicant is exactly what this avoids.
        List<RelayTeamMember> members = relayTeamMemberRepository.findForEventWithUser(event.getId());
        // And the class scope once for the whole page too: the applicant list and the
        // runners each team may still be given are filtered by the same rule. The
        // register those runners come from is read once as well, and shared with the
        // teams below.
        Set<String> mayActFor = mayActFor();
        List<Student> pool = relayPool(event);
        // A relay with no kind is undivided and derives no teams, and none are
        // invented here. It may still have teams made by hand, though — a teacher can
        // build one out of chosen applicants without the event ever being divided —
        // and those are reported, because they are really there and the page has to
        // be able to show and fill them. The applicants are the applicants either
        // way: the page needs them to decide whether this relay can be grouped at all.
        return RelayEventTeamsDTO.of(event, describe(event, teams, members, mayActFor, pool),
                applicantsFor(event, members, mayActFor));
    }

    // ============================================================ the applicants

    /**
     * The students who <strong>applied</strong> to this event — a confirmed entry in
     * it — with the team each already runs for, if any, so the page beside the teams
     * can show who is still unplaced.
     *
     * <h2>Order</h2>
     * <p>Class in school order — form numerically, so Form 2 comes before Form 10 and
     * never after it — then class number, then name, then student id as a last
     * tiebreak. It is the order the register reads in, which is the order a teacher
     * ticks names off in.</p>
     *
     * <h2>Cost</h2>
     * <p>Two queries for the event however many applicants there are: the confirmed
     * entries with their accounts, and the register rows behind them. The teams are
     * read from the leg list the board has already loaded.</p>
     *
     * <h2>Who is on it</h2>
     * <p>The same class rule as everything else, asked once for the page
     * ({@link TeacherClassService#classesMayActFor()}): an administrator sees every
     * applicant, a teacher only the applicants of their own classes — the students
     * they may actually place — and a teacher with no classes sees none. An applicant
     * whose account has no register row has no class, no form and no house, cannot be
     * named in a team at all, and is therefore not on this list.</p>
     *
     * @param members the event's legs, already read, to find who is placed
     */
    /**
     * The classes the signed-in caller may act for, asked <strong>once per page</strong>
     * — the applicant list and the runners a team may still be given are filtered by
     * the same rule, so asking twice would be two queries for one answer.
     */
    private Set<String> mayActFor() {
        return new LinkedHashSet<>(teacherClassService.classesMayActFor());
    }

    private List<RelayApplicantDTO> applicantsFor(Event event, List<RelayTeamMember> members,
                                                  Set<String> mayActFor) {
        if (event.getId() == null) {
            return List.of();
        }
        List<Enrollment> entries = enrollmentRepository.findConfirmedWithUserByEvent(
                event.getId(), Enrollment.EnrollmentStatus.CONFIRMED);
        if (entries.isEmpty()) {
            return List.of();
        }

        Map<Long, RelayTeam> teamByUser = teamByUser(members);

        Set<Long> userIds = new LinkedHashSet<>();
        for (Enrollment entry : entries) {
            if (entry.getUser() != null && entry.getUser().getId() != null) {
                userIds.add(entry.getUser().getId());
            }
        }
        Map<Long, Student> rosters = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Student student : studentRepository.findWithUserByUserIdIn(userIds)) {
                if (student.getUser() != null) {
                    rosters.put(student.getUser().getId(), student);
                }
            }
        }

        List<RelayApplicantDTO> applicants = new ArrayList<>(entries.size());
        for (Enrollment entry : entries) {
            if (entry.getUser() == null || entry.getUser().getId() == null) {
                continue;
            }
            Long userId = entry.getUser().getId();
            Student roster = rosters.get(userId);
            if (roster == null) {
                // An account with no register row has no class, no form and no house,
                // and could not be named in a team at all — the register is what a
                // team takes its runners from, so it is not an applicant to place.
                continue;
            }
            if (!mayActFor.contains(StudentPasswordPolicy.normalizeClass(roster.getClassName()))) {
                // Outside the caller's classes: a teacher is not shown a student they
                // could not place.
                continue;
            }
            RelayTeam team = teamByUser.get(userId);
            applicants.add(RelayApplicantDTO.builder()
                    .userId(userId)
                    .studentRef(roster.getStudentId())
                    .name(roster.getName())
                    .form(roster.getForm())
                    .className(roster.getClassName())
                    .classNumber(roster.getClassNumber())
                    .classLabel(roster.getClassLabel())
                    .house(roster.getHouse())
                    .houseCode(roster.getHouseCode())
                    .teamId(team == null ? null : team.getId())
                    .teamLabel(team == null ? null : team.getLabel())
                    .placed(team != null)
                    .build());
        }
        applicants.sort(APPLICANT_ORDER);
        return applicants;
    }

    /**
     * Which team of this event each athlete already runs for, by user id — read from
     * the legs the board has already loaded.
     *
     * <p>One leg per athlete per event, so first-wins and last-wins are the same
     * answer; the entries are kept in case that ever stops being true. It answers two
     * questions at once: whether an applicant is placed, and whether a student the
     * register offers a team can still be added to it.</p>
     */
    private static Map<Long, RelayTeam> teamByUser(List<RelayTeamMember> members) {
        Map<Long, RelayTeam> teamByUser = new HashMap<>();
        for (RelayTeamMember member : members) {
            if (member.getUser() != null && member.getUser().getId() != null
                    && member.getTeam() != null) {
                teamByUser.putIfAbsent(member.getUser().getId(), member.getTeam());
            }
        }
        return teamByUser;
    }

    /**
     * The register's own order: form numeric, then class, then class number, then
     * name, then student id. Form 2 before Form 10 — a plain string compare puts
     * {@code 10B} first, which is the same trap the team order avoids.
     */
    private static final Comparator<RelayApplicantDTO> APPLICANT_ORDER =
            Comparator.comparingInt((RelayApplicantDTO applicant) ->
                            formNumber(applicant.getForm()))
                    .thenComparing(applicant -> text(applicant.getClassName()))
                    .thenComparingInt(applicant ->
                            applicant.getClassNumber() == null ? Integer.MAX_VALUE
                                    : applicant.getClassNumber())
                    .thenComparing(applicant -> text(applicant.getName()))
                    .thenComparing(applicant -> text(applicant.getStudentRef()));

    /** A sort key for text that is present: case-folded, with nulls last. */
    private static String text(String value) {
        return value == null ? "\uFFFF" : value.trim().toUpperCase(Locale.ROOT);
    }

    // ============================================================== derivation

    /**
     * Creates the teams the roster calls for, and refreshes their labels.
     *
     * <p>Additive on purpose: a team a teacher has already put runners into is never
     * removed, because those selections are not the roster's to throw away. A team
     * that the roster no longer calls for and that has <strong>nobody</strong> in it is
     * only removed when the caller asks to prune.</p>
     *
     * <h2>Which register the teams come from — the event's scope</h2>
     * <ul>
     *   <li>a <strong>form-scoped</strong> event reads every class of
     *       {@link Event#getForm()} <em>across grades</em> — a "Form 1 4x100M" has the
     *       teams {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} whatever grade their
     *       students are in — and takes one team per class;</li>
     *   <li>an event with <strong>no form</strong> is scoped by its grade, exactly as
     *       every event was before the form existed, and reads the event's own grade
     *       for its division. Nothing here changes for those events;</li>
     *   <li>a <strong>house</strong> relay is one team per house within the event's
     *       grade either way, because the kind is what divides it. A house relay has no
     *       form — {@code EventService} refuses to give it one — so a form found on one
     *       is a fault rather than a scope, and it is refused here with the way out
     *       named instead of quietly dividing the race by class.</li>
     * </ul>
     *
     * @param prune also drop empty teams that no longer match the roster
     */
    @Transactional
    public RelayTeamDerivationDTO deriveTeams(Long eventId, boolean prune) {
        Event event = requireEvent(eventId);
        requireRelayEvent(event, "has no relay teams to derive");
        RelayTeamKind kind = event.getRelayTeamKind();
        if (kind == null) {
            throw new IllegalStateException(event.getName() + " has not been divided into form or "
                    + "house teams, so there is nothing to derive. Set the event's relay team kind "
                    + "to FORM or HOUSE first.");
        }
        if (kind != RelayTeamKind.FORM && event.isFormScoped()) {
            throw new IllegalStateException(event.getName() + " is a " + kind
                    + " relay but carries the form " + event.getForm().trim() + ", and only a form "
                    + "relay is scoped to a form. Clear the form (send form as an empty string) or "
                    + "make the event a FORM relay before deriving its teams.");
        }
        if (event.getSex() == null) {
            throw new IllegalStateException(event.getName() + " has no division, so its "
                    + "teams cannot be derived from the register.");
        }
        // The grade is what a grade-scoped relay's teams are read from, and it is
        // required of one. A form-scoped relay does not use it at all — its teams come
        // from every class of the form, whatever grade — so a form event is not held to
        // a grade it could not need.
        if (!event.isFormScoped() && event.getGrade() == null) {
            throw new IllegalStateException(event.getName() + " has no grade, so its "
                    + "teams cannot be derived from the register.");
        }

        List<Student> candidates = relayPool(event);
        // The board this returns carries the runners each team may still be given, so
        // the page after a derive offers them without a second read.
        Set<String> mayActFor = mayActFor();

        // The teams the roster calls for: one key per class, or per house, that the
        // event's own scope — its form, or its grade — and division actually contains.
        Set<String> wanted = new LinkedHashSet<>();
        for (Student student : candidates) {
            String key = teamKeyOf(kind, student);
            if (key != null) {
                wanted.add(key);
            }
        }

        List<RelayTeam> existing = relayTeamRepository.findByEventIdOrderByIdAsc(eventId);
        Map<String, RelayTeam> byKey = new HashMap<>();
        for (RelayTeam team : existing) {
            byKey.put(team.getTeamKey(), team);
        }

        int created = 0;
        int kept = 0;
        for (String key : wanted) {
            String label = kind.labelFor(event.getGrade(), key);
            RelayTeam team = byKey.get(key);
            if (team == null) {
                relayTeamRepository.save(RelayTeam.builder()
                        .event(event)
                        .kind(kind)
                        .teamKey(key)
                        .label(label)
                        .build());
                created++;
            } else {
                // A name somebody typed is theirs: deriving refreshes the label the
                // roster would give the team, and leaves a renamed team's name where it
                // is. The key is never touched either way — that is the team's
                // identity, not its name.
                if (!team.isNameOverridden() && !label.equals(team.getLabel())) {
                    team.setLabel(label);
                    relayTeamRepository.save(team);
                }
                kept++;
            }
        }

        // Teams the roster no longer calls for. One with runners is kept whatever the
        // caller asked: dropping it would throw away somebody's selection. A team made
        // by hand is never the roster's to drop at all — it is not on the roster, its
        // name is the school's own, and pruning it would delete a team nobody derived.
        Set<Long> teamsWithRunners = teamIdsWithRunners(eventId);
        int pruned = 0;
        int keptWithRunners = 0;
        int keptHandMade = 0;
        for (RelayTeam team : existing) {
            if (team.isHandMade()) {
                keptHandMade++;
                continue;
            }
            if (wanted.contains(team.getTeamKey())) {
                continue;
            }
            if (teamsWithRunners.contains(team.getId())) {
                keptWithRunners++;
                continue;
            }
            if (prune) {
                relayTeamRepository.delete(team);
                pruned++;
            } else {
                // An empty team nobody can run in any more, left in place because
                // pruning was not asked for.
                kept++;
            }
        }
        relayTeamRepository.flush();

        List<RelayTeam> after = relayTeamRepository.findByEventIdOrderByIdAsc(eventId);
        List<RelayTeamMember> afterMembers =
                relayTeamMemberRepository.findForEventWithUser(eventId);
        log.info("Derived {} relay teams for event {} ({} {}): {} created, {} kept, {} pruned, "
                        + "{} kept with runners, {} made by hand left alone",
                after.size(), event.getId(), event.getName(), kind, created, kept, pruned,
                keptWithRunners, keptHandMade);

        return RelayTeamDerivationDTO.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .kind(kind.name())
                .created(created)
                .kept(kept)
                .pruned(pruned)
                .keptWithRunners(keptWithRunners)
                .eligibleStudents(candidates.size())
                .board(RelayEventTeamsDTO.of(event,
                        describe(event, after, afterMembers, mayActFor, candidates),
                        applicantsFor(event, afterMembers, mayActFor)))
                .build();
    }

    /** The class name or the house name a student would run under, or null. */
    private static String teamKeyOf(RelayTeamKind kind, Student student) {
        if (kind == RelayTeamKind.FORM) {
            return classKeyOf(student.getClassName());
        }
        return houseOf(student);
    }

    // ========================================================== a team by hand

    /**
     * Creates a relay team out of students the caller chose, under a name they typed.
     *
     * <p>Requirement, as the school wrote it: "teacher can select student who applied
     * relay event and create a relay team". A derived team is one class's or one
     * house's by construction; this is the other half — a team the school makes up
     * itself, called whatever it writes on the sheet. What it may <em>not</em> do is
     * mix: the rule comes from the event's kind, exactly as it does for a derived
     * team, so a form-class relay's hand-made team is still one class and a house
     * relay's is still one house.</p>
     *
     * <h2>The key, and why a derive cannot touch this team</h2>
     * <p>A hand-made team is keyed with <strong>its own name</strong> as
     * {@link RelayTeam#getTeamKey()}, carries <strong>no</strong>
     * {@link RelayTeam#getKind()} at all — whatever kind the event has — and is marked
     * {@link RelayTeam#isHandMade()}.</p>
     *
     * <p>The empty kind is the structural half of the guarantee: a derive builds its
     * wanted key set from class names ({@code FORM}) or house names ({@code HOUSE}),
     * so a team with no kind is never in that set. It is therefore never matched by
     * key, never renamed from the roster, and never pruned. The {@code handMade} mark
     * is the explicit half, so a future rule cannot quietly start treating it as a
     * roster team.</p>
     *
     * <h2>The runners</h2>
     * <p>They are written in the order they are given, so <strong>leg 1 is the first
     * student listed</strong>. Every eligibility rule {@link #addRunner} enforces is
     * enforced here by calling the same methods — the caller may help each student,
     * the student is on this year's list, is in the event's division and grade, is not
     * already running for another team of this event, is in the team's class or house,
     * and the team has room — so a team made in one request and a team filled one
     * runner at a time cannot disagree about who may run.</p>
     *
     * <p>The team does not exist yet while the runners are judged, so it has no
     * members to read a group from: the squad being built is what the group is read
     * from, and the first runner named fixes it. Everybody after them has to be in it,
     * which is what makes a mixed squad refused rather than written.</p>
     *
     * <p>Every runner is judged before anything is written, so a request that is
     * going to be refused leaves nothing behind at all — no team on the board and no
     * runners to roll back.</p>
     *
     * @param eventId the relay event the team belongs to
     * @param request the name the school writes, and the chosen students in leg order
     * @return the created team, with its runners
     */
    @Transactional
    public RelayTeamDTO createTeam(Long eventId, RelayTeamCreateRequest request) {
        Event event = requireEvent(eventId);
        requireRelayEvent(event, "has no relay teams to create");
        if (event.getEffectiveRelayTeamSize() <= 0) {
            throw new IllegalStateException(event.getName() + " has no relay team size set, so "
                    + "no team of it can be filled.");
        }

        String name = requireNameIsUsable(request == null ? null : request.getName());
        List<Long> userIds = request == null || request.getUserIds() == null
                ? List.of()
                : request.getUserIds();
        // A null id inside the list is refused before anything is read, so the refusal
        // names the request rather than whatever the repository makes of a null. Read
        // with a loop rather than List.of(...), which throws on the null itself before
        // this could ever say why.
        for (Long userId : userIds) {
            if (userId == null) {
                throw new IllegalArgumentException("A relay team's runners are named by their "
                        + "student accounts, so none of them can be empty.");
            }
        }
        Set<Long> chosen = new LinkedHashSet<>(userIds);
        if (chosen.size() != userIds.size()) {
            throw new IllegalArgumentException("The same student is listed twice in \"" + name
                    + "\" — an athlete runs one leg of a relay, so name each of them once.");
        }
        requireRoomForSquad(event, name, userIds.size());
        requireNameIsFreeInEvent(event, name);

        // Every runner is judged BEFORE anything is written. The rules are the same
        // method calls addRunner makes, so a runner accepted here would have been
        // accepted there; doing them first means a request that is going to be refused
        // does not leave a half-built team behind for the rollback to tidy up, and the
        // refusal names the very first runner who cannot run rather than the last.
        List<Student> squad = new ArrayList<>(userIds.size());
        RelayTeam candidate = teamCandidate(event, name);
        for (Long userId : userIds) {
            Student student = studentRepository.findWithUserByUserId(userId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No student is on the register for account " + userId + "."));
            teacherClassService.requireMayHelp(student);
            requireNotRunningInEvent(event, student);
            // The team does not exist yet, so it has no members to ask about: the squad
            // built so far is what a hand-made team's group is read from, and the first
            // runner named fixes it. Nothing has been written, so the squad is handed
            // over as rows the rule can read.
            requireEligibleForTeam(candidate, event, student,
                    squadRows(squad), squad.isEmpty() ? student : squad.get(0));
            squad.add(student);
        }

        RelayTeam team = candidate;
        relayTeamRepository.saveAndFlush(team);

        int leg = 1;
        for (Student student : squad) {
            relayTeamMemberRepository.save(RelayTeamMember.builder()
                    .team(team)
                    .user(student.getUser())
                    .leg(leg++)
                    .build());
        }
        relayTeamMemberRepository.flush();
        log.info("Relay team '{}' created by hand for {} with {} runner(s)", name, event.getName(),
                squad.size());

        return describeOne(team);
    }

    /**
     * The team this request is about to make: keyed with the school's own name, of no
     * kind at all, and marked by hand.
     *
     * <p>Built — but not saved — before the runners are judged, so the eligibility
     * rules can be asked about it without writing anything. A hand-made team has no
     * class or house of its own to be judged by, which is exactly why the rule is read
     * from the event instead.</p>
     */
    private static RelayTeam teamCandidate(Event event, String name) {
        return RelayTeam.builder()
                .event(event)
                .kind(null)
                .teamKey(name)
                .label(name)
                .handMade(true)
                .nameOverridden(true)
                .build();
    }

    /**
     * Refuses a squad larger than the event's own team — four runners and, when the
     * event allows one, a single reserve.
     *
     * <p>Checked once for the whole request rather than runner by runner, so a request
     * that names six is refused as a whole and names the ceiling, instead of being
     * half-written and then refused on the fifth or sixth. What the ceiling
     * <em>is</em> stays the event's business
     * ({@link Event#getRelayMemberCap()}), as it is in
     * {@link #requireRoomForAnother}.</p>
     */
    private void requireRoomForSquad(Event event, String name, int size) {
        int cap = event.getRelayMemberCap();
        if (size <= cap) {
            return;
        }
        int legs = event.getEffectiveRelayTeamSize();
        int reserves = cap - legs;
        throw new IllegalStateException("\"" + name + "\" names " + size + " runners, and "
                + event.getName() + " gives a team " + legs + " leg(s)"
                + (reserves > 0 ? " and " + reserves + " reserve(s)" : " and no reserves")
                + " — at most " + cap + " in all.");
    }

    /**
     * Whether a squad of that size fits an event's own team — the ceiling, asked
     * without a name to put in the refusal.
     *
     * <p>This is {@link #requireRoomForSquad} reduced to a yes or no, and it exists
     * for the one caller that is judging a squad against an event it is <em>not</em>
     * moving to yet: {@link #requireTeamFitsEvent}, which asks whether a team already
     * filled under a draft would still fit the event its teams are being moved onto.
     * Asserted to agree with {@link #requireRoomForSquad} rather than copied from it,
     * so the two cannot drift into disagreeing about how big a team may be.</p>
     */
    private static boolean squadFits(Event event, int size) {
        return size <= event.getRelayMemberCap();
    }

    // ================================================= a draft's teams, moved

    /**
     * Re-points every relay team of a <strong>draft</strong> event onto the real relay
     * event, in one go.
     *
     * <h2>The requirement</h2>
     * <p>"create relay event base on selected relay team": the school builds its relay
     * teams by hand first and then wants the race created around them. A relay team
     * cannot live without an event ({@link RelayTeam#getEvent()} is a non-null foreign
     * key, and the marking sheet and the mark grid both reach a team through its
     * event), so the teams are collected on a draft event
     * ({@code EventService.createDraftEvent}) and this is the step that carries them
     * onto the race the school actually runs.</p>
     *
     * <h2>Every rule is re-checked against the destination</h2>
     * <p>Nothing is silently renamed, re-keyed or dropped to make the move fit. The
     * destination is judged before anything moves:</p>
     * <ol>
     *   <li><strong>the source is a draft relay event.</strong> A team of a real
     *       event is a selection already made for a race the school is running, and
     *       moving it would quietly empty that race;</li>
     *   <li><strong>the target is a relay event</strong> — the same
     *       {@link #requireRelayEvent} every other operation asks;</li>
     *   <li><strong>the legs agree.</strong> A team of a squad the target cannot hold
     *       is refused: every runner of it already holds a leg under the draft, so
     *       the legs cannot be renumbered away. A target that runs more legs is
     *       fine;</li>
     *   <li><strong>reserves.</strong> A squad past the target's legs only fits if
     *       the target allows a reserve, exactly as
     *       {@link #requireRoomForAnother} would judge it;</li>
     *   <li><strong>every runner.</strong> Each one must still be on this year's list,
     *       in the target's own division and grade, and in the one group the target's
     *       event kind runs for — {@link #requireEligibleForTeam}, the same rule, so a
     *       team built on a draft cannot be moved onto a race it does not fit;</li>
     *   <li><strong>every name.</strong> A name already used in the target event
     *       refuses the whole move — {@link #requireNameIsFree}, the very same rule
     *       {@link #createTeam} and {@link #renameTeam} ask, asked once per team
     *       against the destination. A marking sheet lists the teams of a race by
     *       name, so two teams sharing one is a result nobody could read off.</li>
     * </ol>
     *
     * <h2>All or nothing, twice over</h2>
     * <p>First by construction: <em>every</em> team is judged — legs, runners and name
     * — before <em>any</em> team is re-pointed, so the refusal names the first thing
     * that cannot move rather than leaving a half-moved board. Then by the
     * transaction: the method is {@code @Transactional}, and the writes end with a
     * flush, so a team that trips the database's own unique key
     * ({@code (event_id, kind, team_key)}) rolls the whole move back rather than
     * leaving half of it in place.</p>
     *
     * <h2>What happens to the draft</h2>
     * <p>The draft event is <strong>kept</strong>, emptied of its teams, with its kind,
     * grade and division untouched. Deleting it is the one thing that must not happen
     * as a side effect: {@code EventService.deleteEvent} removes an event's teams with
     * it, so a delete here would destroy the very selections being moved.</p>
     *
     * @param draftEventId  the draft relay event whose teams are moving
     * @param targetEventId the real relay event they are moving onto
     * @return how many teams and runners moved, and the target event
     */
    @Transactional
    public RelayTeamMoveDTO moveTeamsToEvent(Long draftEventId, Long targetEventId) {
        if (targetEventId == null) {
            throw new IllegalArgumentException("Send the target event's id: the event these teams "
                    + "are to run in. It has to be a relay event.");
        }
        Event draft = requireEvent(draftEventId);
        Event target = requireEvent(targetEventId);
        requireRelayEvent(draft, "have relay teams to move");
        requireRelayEvent(target, "be moved onto");
        if (!draft.isDraft()) {
            throw new IllegalStateException(draft.getName() + " is not a draft relay event, so its "
                    + "teams are not waiting to be moved. A move carries the teams of the event the "
                    + "school built them on; a real event's teams are already racing in it. Create a "
                    + "draft relay event to build teams for " + target.getName() + ".");
        }
        if (draft.getId().equals(target.getId())) {
            throw new IllegalArgumentException(draft.getName() + " is the same event as the "
                    + "target, so its teams are already on it.");
        }

        List<RelayTeam> teams = relayTeamRepository.findByEventIdOrderByIdAsc(draft.getId());

        // ---- every rule, before a single write -------------------------------
        // The legs. A target with fewer legs than a squad already holds cannot take
        // it: the runners are on the legs they are on, and renumbering them away
        // would break the ceiling the mark grid works to.
        requireLegsAgree(draft, target, teams);
        for (RelayTeam team : teams) {
            int size = relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId()).size();
            requireTeamFitsEvent(team, target, size);
            requireRunnersEligibleForTarget(team, target);
        }
        for (RelayTeam team : teams) {
            // The shared rule, asked once per team against the destination. A team
            // already on the target is exempt from colliding with itself — and it is
            // never reached, because a draft's teams are all on the draft.
            requireNameIsFreeInEvent(target, team.getId(), label(team));
        }

        // ---- the move ---------------------------------------------------------
        List<String> labels = new ArrayList<>(teams.size());
        int runners = 0;
        for (RelayTeam team : teams) {
            runners += relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId()).size();
            team.setEvent(target);
            relayTeamRepository.save(team);
            labels.add(label(team));
        }
        // Flushed inside the transaction, so a duplicate the service could not see
        // (two teams racing to write the same key) is refused now and rolls back —
        // rather than surfacing after the response has already said the move worked.
        relayTeamRepository.flush();
        log.info("Moved {} relay team(s) with {} runner(s) from draft relay event {} ('{}') to "
                        + "event {} ('{}')", labels.size(), runners, draft.getId(), draft.getName(),
                target.getId(), target.getName());

        RelayTeamMoveDTO moved = RelayTeamMoveDTO.of(draft, target, labels, runners);
        moved.setTargetEvent(EventDTO.from(target));
        return moved;
    }

    /**
     * Refuses a move when the target's race is shorter than a squad already holds.
     *
     * <p>A team's runners are on legs 1..n, and {@link #requireLegIsFree} is what keeps
     * a leg inside the event's own size. A team that already holds five legs cannot be
     * put on a four-leg race without either cutting a runner or leaving a leg the
     * event says does not exist, and neither is the school's to have decided by a
     * move. A target that runs <em>more</em> legs is fine: a four-runner team in a
     * longer squad is a complete team still being collected.</p>
     */
    private void requireLegsAgree(Event draft, Event target, List<RelayTeam> teams) {
        int draftLegs = draft.getEffectiveRelayTeamSize();
        int targetLegs = target.getEffectiveRelayTeamSize();
        if (targetLegs <= 0) {
            throw new IllegalStateException(target.getName() + " has no relay team size set, so no "
                    + "team of it can be filled.");
        }
        if (targetLegs >= draftLegs) {
            return;
        }
        for (RelayTeam team : teams) {
            int size = relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId()).size();
            if (size > targetLegs) {
                throw new IllegalStateException("\"" + label(team) + "\" holds " + size
                        + " runner(s) and " + target.getName() + " gives a team only "
                        + targetLegs + " leg(s), so the move would leave runners with no leg to "
                        + "run. Move the team onto a longer relay, or take a runner out of it "
                        + "first.");
            }
        }
    }

    /**
     * Whether a squad of that size fits the event it is being moved onto —
     * {@link #requireRoomForSquad}'s rule, and the same answer as
     * {@link #requireRoomForAnother} would give for one more runner.
     */
    private void requireTeamFitsEvent(RelayTeam team, Event target, int size) {
        if (squadFits(target, size)) {
            return;
        }
        requireRoomForSquad(target, label(team), size);
    }

    /**
     * Every runner of a team, re-judged against the event they are moving to: on this
     * year's list, in that event's own division and grade, and in the one group that
     * event's kind runs for.
     *
     * <p>The rule is asked of the same team, one runner at a time, so the class or
     * house it is judged against is its own — its members are what a hand-made team's
     * group is read from, and a derived team's key stands on its own. The draft was
     * made for one grade and division and the target may be another, which is
     * precisely what this catches.</p>
     */
    private void requireRunnersEligibleForTarget(RelayTeam team, Event target) {
        for (RelayTeamMember member : relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId())) {
            Long userId = member.getUser() == null ? null : member.getUser().getId();
            if (userId == null) {
                continue;
            }
            Student student = studentRepository.findWithUserByUserId(userId).orElseThrow(() ->
                    new ResourceNotFoundException("No student is on the register for account "
                            + userId + ", who runs for \"" + label(team) + "\", so the team cannot "
                            + "be moved."));
            requireEligibleForTeam(team, target, student);
        }
    }

    /** What a team is shown as, with a readable fallback for a row that has no name. */
    private static String label(RelayTeam team) {
        return team.getLabel() == null ? "team " + team.getId() : team.getLabel();
    }

    // ================================================================= runners

    /**
     * NAMES a runner for a leg. Every eligibility rule is checked here — see the
     * class notes — so an ineligible runner is refused with a reason rather than
     * quietly accepted by one endpoint and refused by another.
     *
     * @param leg the leg to run, 1-based; {@code null} takes the next free one
     */
    @Transactional
    public RelayTeamDTO addRunner(Long teamId, Long userId, Integer leg) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "A runner is required — send the student's userId.");
        }
        RelayTeam team = requireTeam(teamId);
        Event event = team.getEvent();
        requireRelayEvent(event, "has no relay teams");
        requireKindInStep(team, event);

        Student student = studentRepository.findWithUserByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No student is on the register for account " + userId + "."));

        // 1. Who may do it. The class rule lives in TeacherClassService and is not
        //    restated here: an administrator is let through, a teacher only for a
        //    student in one of their own classes, anybody else not at all.
        teacherClassService.requireMayHelp(student);

        // 2-4. On this year's list, in the event's division and grade, and in the one
        //      group the event's kind runs for — for a team made by hand as much as
        //      for a derived one.
        requireEligibleForTeam(team, event, student);

        // 5. One leg per athlete per event, across teams.
        requireNotRunningInEvent(event, student);

        // 6. Room in the team, and a leg that is free.
        requireRoomForAnother(team, event);
        int target = leg == null ? nextFreeLeg(team) : leg;
        requireLegIsFree(team, event, target);

        RelayTeamMember member = RelayTeamMember.builder()
                .team(team)
                .user(student.getUser())
                .leg(target)
                .build();
        relayTeamMemberRepository.save(member);
        relayTeamMemberRepository.flush();
        log.info("Athlete {} added to relay team '{}' ({}) as leg {}", student.getStudentId(),
                team.getLabel(), event.getName(), target);

        return describeOne(team);
    }

    /**
     * Removes a runner from a team. The remaining legs are closed up — 1, 2, 3 —
     * so a relay order never has a hole in it, and the next runner takes the free
     * last leg.
     *
     * <p>The caller must be allowed to help the runner being removed, so a teacher
     * cannot pull a student out of a team they have no business in.</p>
     */
    @Transactional
    public RelayTeamDTO removeRunner(Long teamId, Long userId) {
        RelayTeam team = requireTeam(teamId);
        Event event = team.getEvent();
        requireRelayEvent(event, "has no relay teams");

        RelayTeamMember member = relayTeamMemberRepository.findByTeamIdAndUserId(team.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That athlete is not in " + team.getLabel() + "."));

        Student student = studentRepository.findWithUserByUserId(userId).orElse(null);
        if (student != null) {
            teacherClassService.requireMayHelp(student);
        } else {
            // No roster row: there is nothing to match a class against, so only an
            // administrator may remove the leg.
            teacherClassService.requireMayHelpUserId(userId);
        }

        relayTeamMemberRepository.delete(member);
        relayTeamMemberRepository.flush();
        compactLegs(team);
        log.info("Athlete {} removed from relay team '{}' ({})", userId, team.getLabel(),
                event.getName());

        return describeOne(team);
    }

    /**
     * Sets the running order: leg 1 first.
     *
     * <p>The request must name exactly the runners the team already has. A teacher
     * may only reorder a team whose every runner they may help, because the request
     * changes all of their legs at once.</p>
     */
    @Transactional
    public RelayTeamDTO reorderLegs(Long teamId, List<Long> userIds) {
        RelayTeam team = requireTeam(teamId);
        Event event = team.getEvent();
        requireRelayEvent(event, "has no relay teams");

        List<RelayTeamMember> members = relayTeamMemberRepository.findByTeamIdOrderByLegAsc(teamId);
        if (members.isEmpty()) {
            throw new IllegalStateException("There is nothing to reorder: " + team.getLabel()
                    + " has no runners yet.");
        }
        if (userIds == null || userIds.isEmpty()) {
            throw new IllegalArgumentException("Send the runners in the order they are to run — "
                    + team.getLabel() + " has " + members.size() + " runner(s).");
        }

        // The caller must be allowed to help everybody whose leg this changes.
        for (RelayTeamMember member : members) {
            Long memberUserId = member.getUser() == null ? null : member.getUser().getId();
            Student student = memberUserId == null ? null
                    : studentRepository.findWithUserByUserId(memberUserId).orElse(null);
            if (student != null) {
                teacherClassService.requireMayHelp(student);
            } else {
                teacherClassService.requireMayHelpUserId(memberUserId);
            }
        }

        Set<Long> requested = new LinkedHashSet<>(userIds);
        if (requested.size() != userIds.size()) {
            throw new IllegalArgumentException("The running order names the same athlete twice — "
                    + "each of " + team.getLabel() + "'s runners runs one leg.");
        }
        Map<Long, RelayTeamMember> byUser = new HashMap<>();
        for (RelayTeamMember member : members) {
            byUser.put(member.getUser() == null ? null : member.getUser().getId(), member);
        }
        if (requested.size() != members.size() || !byUser.keySet().equals(requested)) {
            throw new IllegalArgumentException("The running order must name exactly the "
                    + members.size() + " runner(s) " + team.getLabel() + " already has. Add or "
                    + "remove a runner first, then set the order.");
        }

        // Two phases, because (team_id, leg) is unique: every leg is moved out of the
        // way first, then the new order is written. Otherwise swapping leg 1 and leg 2
        // would collide with the row that still holds the number being written.
        int escape = -1;
        for (RelayTeamMember member : members) {
            member.setLeg(escape--);
        }
        relayTeamMemberRepository.saveAll(members);
        relayTeamMemberRepository.flush();

        List<RelayTeamMember> reordered = new ArrayList<>(members.size());
        int leg = 1;
        for (Long userId : userIds) {
            RelayTeamMember member = byUser.get(userId);
            member.setLeg(leg++);
            reordered.add(member);
        }
        relayTeamMemberRepository.saveAll(reordered);
        relayTeamMemberRepository.flush();
        log.info("Relay team '{}' ({}) set to running order {}", team.getLabel(), event.getName(),
                userIds);

        return describeOne(team);
    }

    /**
     * Removes every relay team of an event, with their runners — an administrator's
     * deliberate fresh start for one race's selections.
     *
     * <p><strong>A draft's teams are not this call's to remove.</strong> A draft relay
     * event exists for no other reason than to hold the teams the school built by
     * hand, so clearing it would destroy exactly the selections it was made to keep —
     * and it is precisely what a delete of the draft would do as a side effect
     * ({@code EventService.deleteEvent}). Dropping a draft's teams is therefore its
     * own, explicit request ({@link #discardDraftTeams}). An ordinary event is
     * unaffected.</p>
     *
     * @return how many teams were removed
     */
    @Transactional
    public int removeTeamsForEvent(Long eventId) {
        Event event = requireEvent(eventId);
        if (event.isDraft()) {
            long teams = relayTeamRepository.countByEventId(eventId);
            if (teams > 0) {
                throw new IllegalStateException(event.getName() + " is a draft relay event and "
                        + "still holds " + teams + " relay team(s). Those teams are the selections "
                        + "the draft was made to carry, so they are not cleared from here — move "
                        + "them onto the real event (POST /api/admin/relay-events/" + eventId
                        + "/teams/move). To abandon the draft's teams deliberately, discard them "
                        + "with DELETE /api/admin/relay-events/" + eventId + "/teams, which is its "
                        + "own request and cannot happen by accident.");
            }
        }
        return clearTeams(event);
    }

    /**
     * Throws away a draft relay event's teams, with their runners — the one
     * deliberate way to empty a draft, and the only thing that lets the draft itself
     * be deleted afterwards.
     *
     * <p>It is separate from {@link #removeTeamsForEvent} for a reason that is about
     * the school's data rather than tidiness: {@code removeTeamsForEvent} is called by
     * {@code EventService.deleteEvent}, so if a draft's delete went through it, the
     * teams — the whole point of the draft — would be destroyed as a side effect of
     * removing an event. Here the caller has asked for exactly that, on its own
     * endpoint, and the draft is left in place and empty.</p>
     *
     * @return how many teams were removed
     */
    @Transactional
    public int discardDraftTeams(Long draftEventId) {
        Event draft = requireEvent(draftEventId);
        if (!draft.isDraft()) {
            throw new IllegalStateException(draft.getName() + " is not a draft relay event, so its "
                    + "teams are not discarded this way. Use "
                    + "DELETE /api/admin/events/" + draftEventId + "/relay-teams.");
        }
        int removed = clearTeams(draft);
        log.info("Discarded {} relay team(s) of draft event {} deliberately", removed, draftEventId);
        return removed;
    }

    /** Removes an event's teams and their legs. The one place that deletes them. */
    private int clearTeams(Event event) {
        List<RelayTeam> teams = relayTeamRepository.findByEventIdOrderByIdAsc(event.getId());
        if (teams.isEmpty()) {
            return 0;
        }
        for (RelayTeam team : teams) {
            relayTeamMemberRepository.deleteByTeamId(team.getId());
        }
        relayTeamMemberRepository.flush();
        relayTeamRepository.deleteAll(teams);
        relayTeamRepository.flush();
        log.info("Removed {} relay team(s) of event {}", teams.size(), event.getId());
        return teams.size();
    }

    // ============================================================ the rule book

    /**
     * Whether a student may run in a given team — <strong>the one place that
     * decides it</strong>, and the same rule whether the request names a whole squad
     * ({@link #createTeam}), one runner ({@link #addRunner}) or every runner of a team
     * being moved ({@link #requireRunnersEligibleForTarget}).
     *
     * <ol>
     *   <li>the student is on this year's list;</li>
     *   <li>the student is in the event's own division and scope
     *       ({@link #requireEventIsForTheStudent}) — its grade, or its form on a
     *       form-scoped event — which is the rule an entry is judged by as well;</li>
     *   <li>the student is in the <strong>one group the team runs for</strong>.</li>
     * </ol>
     *
     * <h2>Where the group comes from</h2>
     * <p>The rule comes from the <strong>event's</strong>
     * {@link Event#getRelayTeamKind()}, never from the team's own
     * {@link RelayTeam#getKind()}. That is deliberate: a team made by hand carries no
     * kind at all — which is exactly what keeps a derive from matching, renaming or
     * pruning it — so judging it by its own kind would exempt the very team shape the
     * school most needs the rule for, and let a teacher put a {@code 1A} and a
     * {@code 2A} student into one team. A form-class relay takes one class and a house
     * relay takes one house, whoever built the team.</p>
     *
     * <p>What the group <em>is</em> is then read in one of two ways, both of which land
     * in the same comparison below:</p>
     * <ul>
     *   <li>a <strong>derived</strong> team states its own group: its key is the class
     *       name the derive keyed it with, or the house name. A team keyed with a bare
     *       form number, derived before the class split, still takes its own form's
     *       runners;</li>
     *   <li>a <strong>hand-made</strong> team has no such key — its key is the name the
     *       school typed, {@code B Grade Yellow} — so the group is whoever is already
     *       on it, and, before anything is written, the first runner of the squad being
     *       judged. The first runner named fixes the group and everybody after them has
     *       to be in it, which is what makes the rule hold for a squad judged in one
     *       request and for runners added one at a time.</li>
     * </ul>
     *
     * <p>An event with <strong>no kind</strong> is undivided: it has no class rule and
     * no house rule to break, so the division and the grade above are the whole of it
     * and a hand-made team of any students is allowed. That is the state a relay is in
     * while its teams are still being put together, and refusing it would forbid the
     * very flow the school uses.</p>
     *
     * <h2>Grade on a house relay, and grade on a form relay</h2>
     * <p>A member of another grade is refused by {@link #requireEventIsForTheStudent}
     * above, before any group is read: the event belongs to one grade, so an athlete
     * of another grade can never be in its division-and-grade in the first place. No
     * separate grade test is needed here.</p>
     *
     * <p>A <strong>form-scoped</strong> event is judged the other way round by that
     * same rule: the form must match and the grade is not asked about at all, so a
     * Form 1 runner of any grade may hold a leg in the Form 1 relay. The class rule
     * below still holds — the team is one class's — so a form relay's {@code 1A} team
     * refuses a Form 1 runner from {@code 1B}.</p>
     *
     * @param seen      a hand-made team's runners, asked for the group: the teammates
     *                  already on the team, or the squad judged so far while a team is
     *                  still being built and has none of its own
     * @param reference the first runner of the squad being judged, who fixes the group
     *                  for a team that has none of its own
     */
    private void requireEligibleForTeam(RelayTeam team, Event event, Student student,
                                        List<RelayTeamMember> seen, Student reference) {
        if (!Boolean.TRUE.equals(student.getEnabled())) {
            throw new IllegalStateException(student.getName() + " is locked because they are not "
                    + "on this year's student list, so they cannot be named in a relay team.");
        }
        // Division and scope — the rule entries are judged by. It is restated here
        // (with the athlete named, rather than addressed as "you") only because the
        // original is private inside EnrollmentService; the semantics are identical,
        // and the two must be changed together.
        requireEventIsForTheStudent(student, event);

        RelayTeamKind kind = event.getRelayTeamKind();
        if (kind == null) {
            // An undivided relay: no class rule and no house rule to break.
            return;
        }

        GroupReference group = groupReferenceOf(team, kind, seen, reference);
        if (group == null) {
            // Nothing on the team yet and nobody to take a group from: there is no group
            // to be outside of, so there is nothing here to refuse.
            return;
        }
        String mine = group.keyOf(student);
        if (mine == null) {
            throw new IllegalStateException(student.getName() + " is not on the register with a "
                    + group.what() + ", so they cannot be named in a team of " + event.getName()
                    + ", which runs by " + group.what() + ".");
        }
        if (group.matches(mine)) {
            return;
        }
        // The refusal names the athlete who does not belong, the group they are in and
        // the group the team runs for, in that order: a teacher reads who, then why.
        // A house reads "Blue House" — the school's own way of writing it — while a
        // class is already written in full by its name.
        throw new IllegalStateException(student.getName() + " is in " + group.assignment(mine)
                + ", and " + group.display() + " runs for this team only — a " + group.relayName()
                + " relay team cannot mix " + group.whatPlural() + ".");
    }

    /** The same rule for a runner joining a team that already exists. */
    private void requireEligibleForTeam(RelayTeam team, Event event, Student student) {
        requireEligibleForTeam(team, event, student,
                relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId()), null);
    }

    /**
     * The runners already chosen, as the rows the rule reads a hand-made team's group
     * from before any of them has been written. Their legs are not this rule's
     * business, so a bare count is enough and the order is the order they were named
     * in — the first is the one that fixes the group.
     */
    private static List<RelayTeamMember> squadRows(List<Student> squad) {
        List<RelayTeamMember> rows = new ArrayList<>(squad.size());
        int leg = 1;
        for (Student student : squad) {
            rows.add(RelayTeamMember.builder()
                    .user(student.getUser())
                    .leg(leg++)
                    .build());
        }
        return rows;
    }

    /**
     * The one group a relay team runs for: the class of a form-class relay, or the
     * house of a house relay.
     *
     * <p>A derived team states its own group — its key <em>is</em> the class name or
     * the house name — so a request is judged against the team. A team made by hand has
     * no such key, so its group is the first of its runners, or the first runner of the
     * squad being judged before any of it is written. Both readings end in the same
     * comparison, and there is no third.</p>
     *
     * @param seen      the runners already on the team, in leg order
     * @param reference the first runner of the squad being judged, or null
     */
    private GroupReference groupReferenceOf(RelayTeam team, RelayTeamKind kind,
                                            List<RelayTeamMember> seen, Student reference) {
        boolean byHouse = kind == RelayTeamKind.HOUSE;
        if (!team.isHandMade()) {
            return new GroupReference(byHouse, String.valueOf(team.getTeamKey()).trim());
        }
        for (RelayTeamMember member : seen) {
            if (member.getUser() == null) {
                continue;
            }
            Student teammate = studentRepository.findWithUserByUserId(member.getUser().getId())
                    .orElse(null);
            if (teammate == null) {
                continue;
            }
            String key = byHouse ? houseOf(teammate) : classKeyOf(teammate.getClassName());
            if (key != null) {
                return new GroupReference(byHouse, key);
            }
        }
        String key = reference == null ? null
                : byHouse ? houseOf(reference) : classKeyOf(reference.getClassName());
        // Nothing to read a group from: the team is empty and there is nobody to take one
        // from. Nobody can be refused against a group that does not exist — and on the
        // path below the student's own group is checked to be there anyway.
        return key == null ? null : new GroupReference(byHouse, key);
    }

    /**
     * The one group a relay team runs for, and how a runner is compared against it.
     *
     * <p>Two shapes, one comparison — which is what makes the rule a single rule rather
     * than a copy per kind of team. A class is compared the way the rest of the class
     * handling does it ({@link #classKeyOf}, so case and spacing do not matter) and a
     * house without case, the way its name is written on the sheet.</p>
     */
    private record GroupReference(boolean house, String key) {

        /** The value this group is read from for a student: their class name, or house. */
        String keyOf(Student student) {
            return house ? houseOf(student) : classKeyOf(student.getClassName());
        }

        boolean matches(String value) {
            if (key == null || value == null) {
                return false;
            }
            if (key.equalsIgnoreCase(value)) {
                return true;
            }
            // A form-class team keyed with a bare form number — {@code "1"} — is one
            // derived before the class split. It still takes its own form's runners, so
            // a live team is not left unable to be filled; the next derive adds the class
            // teams beside it. A class name is never a bare number, so the two cases
            // cannot collide.
            return !house && key.equals(formKeyOf(value));
        }

        /** What a runner is in: {@code class} or {@code house}. */
        String what() {
            return house ? "house" : "class";
        }

        /** The plural, for the reason at the end of a refusal. */
        String whatPlural() {
            return house ? "houses" : "classes";
        }

        /** How the rule names itself when it refuses: {@code form} or {@code house}. */
        String relayName() {
            return house ? "house" : "form";
        }

        /**
         * The group as a teacher would say it: the class name, or the house with the
         * word House after it — {@code 1A}, {@code Red House}.
         */
        String display() {
            return house ? key + " House" : key;
        }

        /** The same reading for the athlete's own value: {@code 1A}, or {@code Blue House}. */
        String assignment(String value) {
            return house ? value + " House" : value;
        }
    }

    /**
     * The event's <strong>division and grade</strong>, which is the rule an entry is
     * judged by as well: a runner must be in the event's sex division and in the
     * event's own grade.
     *
     * <p>This mirrors {@code EnrollmentService.requireEventIsForTheStudent}, which is
     * private — the semantics are deliberately identical, and the two must be changed
     * together. The wording is the only difference: a relay runner is named by
     * somebody else, so the refusal names the athlete instead of addressing them as
     * "you".</p>
     */
    private static void requireEventIsForTheStudent(Student student, Event event) {
        if (student.getSex() != null && event.getSex() != null
                && student.getSex() != event.getSex()) {
            throw new IllegalStateException(student.getName() + " is entered as "
                    + student.getSex().getLabel() + ", and " + event.getName() + " is the "
                    + event.getSex().getLabel() + " event, so they cannot run in it.");
        }
        if (event.isFormScoped()) {
            String form = Student.formOf(student.getClassName());
            if (form == null || !form.equalsIgnoreCase(event.getForm().trim())) {
                throw new IllegalStateException(student.getName() + " is in "
                        + (form == null ? "no form" : "Form " + form) + ", and "
                        + event.getName() + " is the Form " + event.getForm().trim()
                        + " event, so they cannot run in it.");
            }
        } else if (student.getGrade() != null && event.getGrade() != null
                && student.getGrade() != event.getGrade()) {
            throw new IllegalStateException(student.getName() + " is in the "
                    + student.getGrade().name() + " grade, and " + event.getName() + " is the "
                    + event.getGrade().getLabel() + " event, so they cannot run in it.");
        }
    }

    /** One athlete, one leg of an event — across every team of it, not just this one. */
    private void requireNotRunningInEvent(Event event, Student student) {
        List<RelayTeamMember> already =
                relayTeamMemberRepository.findForEventAndUser(event.getId(), student.getUser().getId());
        if (already.isEmpty()) {
            return;
        }
        RelayTeam other = already.get(0).getTeam();
        throw new IllegalStateException(student.getName() + " already runs for "
                + (other == null ? "another team" : other.getLabel()) + " in " + event.getName()
                + ". One athlete may hold only one leg of an event.");
    }

    /** The team's size, and whether it has any room left at all. */
    private void requireRoomForAnother(RelayTeam team, Event event) {
        int legs = event.getEffectiveRelayTeamSize();
        if (legs <= 0) {
            throw new IllegalStateException(event.getName() + " has no relay team size set, so "
                    + "no team of it can be filled.");
        }
        int cap = event.getRelayMemberCap();
        long current = relayTeamMemberRepository.countByTeamId(team.getId());
        if (current < cap) {
            return;
        }
        if (!event.isRelayReservesAllowed()) {
            throw new IllegalStateException(team.getLabel() + " already has all " + legs
                    + " of its legs filled. Allow reserves on " + event.getName()
                    + " to name more than " + legs + " runners.");
        }
        throw new IllegalStateException(team.getLabel() + " already has its full squad of " + cap
                + " — " + legs + " legs and " + (cap - legs) + " reserves.");
    }

    /** A leg inside the team's size, and not already filled by somebody else. */
    private void requireLegIsFree(RelayTeam team, Event event, int leg) {
        int cap = event.getRelayMemberCap();
        if (leg < 1 || leg > cap) {
            throw new IllegalArgumentException("A leg must be between 1 and " + cap + ": "
                    + event.getName() + " runs " + event.getEffectiveRelayTeamSize() + " legs"
                    + (event.isRelayReservesAllowed()
                            ? " and allows up to " + (cap - event.getEffectiveRelayTeamSize())
                                    + " reserves."
                            : " and does not allow reserves."));
        }
        if (relayTeamMemberRepository.existsByTeamIdAndLeg(team.getId(), leg)) {
            throw new IllegalStateException("Leg " + leg + " of " + team.getLabel()
                    + " is already taken — set the running order instead of naming the leg.");
        }
    }

    /** The smallest leg nobody holds, so a team fills 1, 2, 3, 4 before any reserve. */
    private int nextFreeLeg(RelayTeam team) {
        Set<Integer> taken = new HashSet<>();
        for (RelayTeamMember member : relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId())) {
            if (member.getLeg() != null) {
                taken.add(member.getLeg());
            }
        }
        int leg = 1;
        while (taken.contains(leg)) {
            leg++;
        }
        return leg;
    }

    /** Closes the gap a removal leaves: the runners keep their order, renumbered 1..n. */
    private void compactLegs(RelayTeam team) {
        List<RelayTeamMember> members = relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId());
        boolean changed = false;
        int leg = 1;
        for (RelayTeamMember member : members) {
            if (member.getLeg() == null || member.getLeg() != leg) {
                member.setLeg(leg);
                changed = true;
            }
            leg++;
        }
        if (changed) {
            relayTeamMemberRepository.saveAll(members);
            relayTeamMemberRepository.flush();
        }
    }

    private void requireRelayEvent(Event event, String because) {
        if (!event.isRelay()) {
            throw new IllegalArgumentException(event.getName() + " is not a relay event, so it "
                    + because + ".");
        }
    }

    /** A team whose kind has drifted from its event's is a fault, not a team. */
    private void requireKindInStep(RelayTeam team, Event event) {
        if (team.isHandMade()) {
            // A hand-made team has no kind to be in step with, and is deliberately not
            // held to the event's: it is not one class's and not one house's, and it may
            // live on an event that has not been divided at all — which is exactly the
            // state a relay is in while its teams are still being put together. The
            // class and house rule is applied from the event instead, in
            // requireEligibleForTeam.
            return;
        }
        if (event.getRelayTeamKind() == null) {
            throw new IllegalStateException(event.getName() + " is no longer divided into form or "
                    + "house teams, so " + team.getLabel() + " cannot be used.");
        }
        if (team.getKind() != event.getRelayTeamKind()) {
            throw new IllegalStateException(team.getLabel() + " is a " + team.getKind()
                    + " team but " + event.getName() + " is now a " + event.getRelayTeamKind()
                    + " relay, so the team no longer belongs to it.");
        }
    }

    // ============================================================== plumbing

    /**
     * Renames a team.
     *
     * <p>The name is what the school writes on the sheet — {@code 1A}, {@code C Grade
     * Yellow} — so it is free text rather than a code, and renaming a team leaves the
     * runners already on it exactly where they are. {@code teamKey} is untouched: it is
     * the team's identity, the thing a derive matches on, and it is not the label.</p>
     */
    @Transactional
    public RelayTeamDTO renameTeam(Long teamId, String rawName) {
        RelayTeam team = requireTeam(teamId);
        requireMayRename(team);
        String name = requireNameIsUsable(rawName);
        requireNameIsFreeInItsEvent(team, name);
        team.setLabel(name);
        // This name is somebody's choice, so a later derive must not put the roster's
        // own label back over it.
        team.setNameOverridden(true);
        relayTeamRepository.save(team);
        return describeOne(team);
    }

    /** A team's name is what fits across a marking sheet, not a novel. */
    private static final int MAX_TEAM_NAME_LENGTH = 40;

    /**
     * A name a sheet can print: trimmed, present, and no longer than
     * {@link #MAX_TEAM_NAME_LENGTH}.
     *
     * <p>The one place the rule lives. Renaming a team
     * ({@link #renameTeam}) and creating one by hand
     * ({@link #createTeam}) both go through it, so the two cannot drift into
     * disagreeing about what a team may be called.</p>
     *
     * @return the trimmed name, ready to store
     */
    private static String requireNameIsUsable(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException(
                    "A relay team needs a name. Send the name the school writes on the sheet.");
        }
        String name = rawName.trim();
        if (name.length() > MAX_TEAM_NAME_LENGTH) {
            throw new IllegalArgumentException("A relay team's name is at most "
                    + MAX_TEAM_NAME_LENGTH + " characters: \"" + name + "\" is "
                    + name.length() + ".");
        }
        return name;
    }

    /**
     * Two teams of one race cannot share a name.
     *
     * <p>A marking sheet and a mark-entry grid list the teams of an event by name, so
     * two teams called the same thing in the same race is a name nobody can read a
     * result off. The derived names never collide — a class and a grade-and-house are
     * each unique within their event — so this only ever catches a name typed in by
     * hand. A team keeping its own name is not a collision with itself, and the
     * comparison ignores case and surrounding space, because "1a Boys" and "1A Boys"
     * are the same name to a reader.</p>
     *
     * <p>This is <strong>the</strong> check, asked three ways: a rename asks it about a
     * team that already exists, {@link #createTeam} asks it about a name it is about to
     * give a new one, and {@link #moveTeamsToEvent} asks it once per team against the
     * event the draft's teams are moving onto. None of the three has a second copy of
     * the rule.</p>
     */
    private void requireNameIsFreeInItsEvent(RelayTeam team, String name) {
        requireNameIsFree(team.getEvent(), team.getId(), name);
    }

    /** The same rule for a team about to be created, which has no id of its own yet. */
    private void requireNameIsFreeInEvent(Event event, String name) {
        requireNameIsFree(event, null, name);
    }

    /**
     * The same rule, asked by the move about the event the teams are going to: is this
     * name free <em>there</em>? A team already on that event is exempt from colliding
     * with itself.
     */
    private void requireNameIsFreeInEvent(Event target, Long exemptTeamId, String name) {
        requireNameIsFree(target, exemptTeamId, name);
    }

    /**
     * @param exemptTeamId the team the name already belongs to, which is not a
     *                     collision with itself; null when the team is being created
     */
    private void requireNameIsFree(Event event, Long exemptTeamId, String name) {
        if (event == null || event.getId() == null) {
            return;
        }
        for (RelayTeam other : relayTeamRepository.findByEventIdOrderByIdAsc(event.getId())) {
            if (exemptTeamId != null && exemptTeamId.equals(other.getId())) {
                continue;
            }
            String otherName = other.getLabel() == null ? "" : other.getLabel().trim();
            if (otherName.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("\"" + name + "\" is already the name of "
                        + "another team in " + event.getName() + ". Two teams of one race "
                        + "cannot share a name — a marking sheet lists them by it.");
            }
        }
    }

    /**
     * Who may rename a team.
     *
     * <p>An administrator may rename any of them. A <strong>class</strong> team belongs
     * to exactly one class, so the test is the same one that governs everything else a
     * teacher does — is that class one of theirs? A <strong>house</strong> team spans
     * many classes and so belongs to no one teacher; it may be renamed by a teacher who
     * looks after at least one athlete named on it, and by nobody else. A house team
     * with an empty roster is therefore an administrator's to name, which is the safe
     * way round.</p>
     */
    private void requireMayRename(RelayTeam team) {
        if (teacherClassService.isAdmin()) {
            return;
        }
        var caller = teacherClassService.signedInUser();
        List<String> mine = teacherClassService.assignedClasses(caller.getId());
        if (team.getKind() == RelayTeamKind.FORM && mine.contains(team.getTeamKey())) {
            return;
        }
        // Read the runners the way everything else in this service does — from the
        // repository — rather than trusting whatever the team's own collection happens
        // to hold. A team loaded without its members would otherwise look empty and be
        // nobody's to name.
        List<RelayTeamMember> runners =
                relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId());
        for (RelayTeamMember member : runners) {
            if (member.getUser() == null) {
                continue;
            }
            try {
                teacherClassService.requireMayHelpUserId(member.getUser().getId());
                return;
            } catch (RuntimeException notTheirs) {
                // Not one of this teacher's athletes; try the next one named.
            }
        }
        throw new org.springframework.security.access.AccessDeniedException(team.getLabel()
                + " is not yours to rename — a teacher may rename a relay team from "
                + "their own classes.");
    }

    private RelayTeam requireTeam(Long teamId) {
        return relayTeamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Relay team not found: " + teamId));
    }

    private Event requireEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));
    }

    /** One team, freshly read, with its runners. */
    private RelayTeamDTO describeOne(RelayTeam team) {
        List<RelayTeamMember> members =
                relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId());
        return RelayTeamDTO.from(team, members, rostersFor(members));
    }

    private List<RelayTeamDTO> describe(Event event, List<RelayTeam> teams,
                                        List<RelayTeamMember> all, Set<String> mayActFor,
                                        List<Student> pool) {
        List<RelayTeam> ordered = new ArrayList<>(teams);
        ordered.sort(TEAM_ORDER);

        Map<Long, List<RelayTeamMember>> byTeam = new LinkedHashMap<>();
        for (RelayTeamMember member : all) {
            Long teamId = member.getTeam() == null ? null : member.getTeam().getId();
            byTeam.computeIfAbsent(teamId, key -> new ArrayList<>()).add(member);
        }
        Map<Long, Student> rosters = rostersFor(all);
        Map<String, List<RelayApplicantDTO>> candidates =
                candidatesByTeamKey(event, all, mayActFor, pool);

        List<RelayTeamDTO> described = new ArrayList<>(ordered.size());
        for (RelayTeam team : ordered) {
            // A team made by hand is no class's and no house's, so the register offers
            // it nobody and it keeps the empty list: it is filled through the tick list
            // and the create form instead.
            List<RelayApplicantDTO> forThisTeam = team.getKind() == null
                    ? List.of()
                    : candidates.getOrDefault(team.getTeamKey(), List.of());
            described.add(RelayTeamDTO.from(team,
                    byTeam.getOrDefault(team.getId(), List.of()), rosters, forThisTeam));
        }
        return described;
    }

    /**
     * The students the register offers each derived team of this event, by the team's
     * key — a class ({@code 1A}) or a house ({@code Red}) — with everyone already
     * running in the event left out.
     *
     * <p><strong>The pool is the one a derive draws from</strong>, so the board offers
     * exactly what a re-derive could place: the event's own scope, its form across
     * grades or its grade, in its division, read through
     * {@link #teamKeyOf(RelayTeamKind, Student)} — the same two calls
     * {@link #deriveTeams(Long, boolean)} makes. A student already on a team of this
     * event is not offered: one leg each, so they are nothing to add.</p>
     *
     * <p>This is what the board's "add a runner" list is built from, and it is why a
     * runner who has just been <strong>removed</strong> from a team appears on it
     * again. It is deliberately not the event's list of applicants: a form relay's
     * teams are one per class of that form, filled from the register, and the students
     * running in it need never have entered it.</p>
     *
     * <p>Empty — and free — for an event that is not divided, and for one whose scope
     * or division is not set, because there is no group to offer anybody for. One
     * query for the register and one for the caller's classes, whatever the number of
     * teams.</p>
     */
    private Map<String, List<RelayApplicantDTO>> candidatesByTeamKey(
            Event event, List<RelayTeamMember> members, Set<String> mayActFor,
            List<Student> pool) {
        RelayTeamKind kind = event.getRelayTeamKind();
        if (kind == null || pool.isEmpty()) {
            return Map.of();
        }

        Map<Long, RelayTeam> placed = teamByUser(members);
        Map<String, List<RelayApplicantDTO>> byKey = new LinkedHashMap<>();
        for (Student student : pool) {
            if (student.getUser() == null || student.getUser().getId() == null) {
                continue;
            }
            if (placed.containsKey(student.getUser().getId())) {
                // Already running in this event, so there is no leg to give them.
                continue;
            }
            if (!mayActFor.contains(StudentPasswordPolicy.normalizeClass(student.getClassName()))) {
                // Outside the caller's classes: the add would be refused, so the board
                // does not offer them either.
                continue;
            }
            String key = teamKeyOf(kind, student);
            if (key == null) {
                continue;
            }
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidateFrom(student));
        }
        for (List<RelayApplicantDTO> candidates : byKey.values()) {
            // The register's own reading order, the same one the applicant list uses.
            candidates.sort(APPLICANT_ORDER);
        }
        return byKey;
    }

    /**
     * The register a divided relay's teams are drawn from — the event's form across
     * grades, or its grade — or none when there is nothing to draw from: an event that
     * has not been divided has no group to offer anybody for, and one without a
     * division or a scope cannot be read.
     *
     * <p>One query, asked by the derive for the teams it makes and by the board for the
     * runners each team may still be given, so a page costs one read of the register
     * however many teams it shows.</p>
     */
    private List<Student> relayPool(Event event) {
        if (event.getRelayTeamKind() == null || event.getSex() == null
                || (!event.isFormScoped() && event.getGrade() == null)) {
            return List.of();
        }
        return event.isFormScoped()
                ? studentRepository.findActiveBySexAndForm(event.getSex(), event.getForm().trim())
                : studentRepository.findActiveBySexAndGrade(event.getSex(), event.getGrade());
    }

    /** One student the register offers a team, in the shape the board renders. */
    private static RelayApplicantDTO candidateFrom(Student student) {
        return RelayApplicantDTO.builder()
                .userId(student.getUser() == null ? null : student.getUser().getId())
                .studentRef(student.getStudentId())
                .name(student.getName())
                .form(student.getForm())
                .className(student.getClassName())
                .classNumber(student.getClassNumber())
                .classLabel(student.getClassLabel())
                .house(student.getHouse())
                .houseCode(student.getHouseCode())
                // A candidate is by definition on no team of this event.
                .placed(false)
                .build();
    }

    private Map<Long, Student> rostersFor(List<RelayTeamMember> members) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (RelayTeamMember member : members) {
            if (member.getUser() != null && member.getUser().getId() != null) {
                userIds.add(member.getUser().getId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Student> byUser = new HashMap<>();
        for (Student student : studentRepository.findWithUserByUserIdIn(userIds)) {
            if (student.getUser() != null) {
                byUser.put(student.getUser().getId(), student);
            }
        }
        return byUser;
    }

    private Set<Long> teamIdsWithRunners(Long eventId) {
        Set<Long> teamIds = new HashSet<>();
        for (RelayTeamMember member : relayTeamMemberRepository.findForEventWithUser(eventId)) {
            if (member.getTeam() != null) {
                teamIds.add(member.getTeam().getId());
            }
        }
        return teamIds;
    }
}
