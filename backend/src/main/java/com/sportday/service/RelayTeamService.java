package com.sportday.service;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.dto.RelayTeamDerivationDTO;
import com.sportday.entity.Event;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Student;
import com.sportday.exception.ResourceNotFoundException;
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
import java.util.Map;
import java.util.Set;

/**
 * Relay teams: deriving them from the register, and deciding who may run in them.
 *
 * <h2>What a relay team is</h2>
 * <p>A relay event may be given a {@link RelayTeamKind}: {@code FORM} gives one team
 * per form of the event's own grade and division (中一 to 中六, from the leading
 * digits of {@link Student#getClassName()}), and {@code HOUSE} gives one team per
 * house within that grade. Because an event already belongs to exactly one grade, a
 * house relay is one team per house present among that grade's students.</p>
 *
 * <p>An event with no kind is simply <strong>undivided</strong> — it has no teams,
 * and nothing here invents any. That is how the relay events already in the
 * programme behave, so this feature adds a choice without taking one away.</p>
 *
 * <h2>The selection rules, in one place</h2>
 * <p>Every rule a teacher could otherwise work around is enforced inside
 * {@link #addRunner}, so no endpoint can bypass it:</p>
 * <ol>
 *   <li>the caller may act for the student at all —
 *       {@link TeacherClassService#requireMayHelp(Student)}, which lets an
 *       administrator help anybody and a teacher only the classes assigned to
 *       them;</li>
 *   <li>the runner is on this year's list — a locked student is not;</li>
 *   <li>the runner is in the <strong>event's division and grade</strong> — the same
 *       rule entry uses, restated here because the original is private inside
 *       {@code EnrollmentService};</li>
 *   <li>a house team's runner is in that team's house; a form team's runner is in
 *       that team's form;</li>
 *   <li>the same athlete cannot hold two legs of one team, and cannot hold two legs
 *       of the same event across teams at all;</li>
 *   <li>the team's size is respected — four legs for a 4x100M, and no more unless
 *       the event explicitly allows reserves.</li>
 * </ol>
 *
 * <h2>The two rules that needed deciding</h2>
 * <ul>
 *   <li><strong>Both kinds of team at once — yes.</strong> An athlete may hold a leg
 *       in a form team and a leg in a house team, because those are different
 *       events: the division and the kind live on the event, so a form relay and a
 *       house relay are two races. They may not hold two legs of the <em>same</em>
 *       event.</li>
 *   <li><strong>Team size and reserves.</strong> The size of the race is the
 *       event's business ({@link Event#getEffectiveRelayTeamSize()}), and it is four
 *       for both relays the programme runs. Going past it is refused unless the
 *       event has explicitly allowed reserves
 *       ({@link Event#isRelayReservesAllowed()}), and then the ceiling is twice the
 *       race's legs — {@link Event#RESERVE_ALLOWANCE_MULTIPLIER} — so a 4x100M that
 *       allows reserves may put down eight runners: four legs and four reserves.</li>
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

    /** Forms in numerical order, then houses alphabetically. */
    private static final Comparator<RelayTeam> TEAM_ORDER = (left, right) -> {
        int byKind = Integer.compare(kindOrder(left.getKind()), kindOrder(right.getKind()));
        if (byKind != 0) {
            return byKind;
        }
        if (left.getKind() == RelayTeamKind.FORM && right.getKind() == RelayTeamKind.FORM) {
            int byForm = Integer.compare(formNumber(left.getTeamKey()), formNumber(right.getTeamKey()));
            if (byForm != 0) {
                return byForm;
            }
        }
        return String.valueOf(left.getTeamKey()).compareToIgnoreCase(String.valueOf(right.getTeamKey()));
    };

    // ================================================================= the form

    /**
     * The form a class belongs to: the leading run of digits of the class name, so
     * {@code 1A}, {@code 1B} and {@code 1C} are all Form 1, and {@code 10B} is
     * Form 10 rather than Form 1. Leading zeros are dropped, so {@code 01A} is
     * Form 1 as well.
     *
     * @return the form as a string, or {@code null} when the class name does not
     *         start with a digit and so names no form
     */
    public static String formKeyOf(String className) {
        if (className == null) {
            return null;
        }
        String trimmed = className.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        String digits = trimmed.substring(0, end);
        int firstSignificant = 0;
        while (firstSignificant < digits.length() - 1 && digits.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        return digits.substring(firstSignificant);
    }

    private static int formNumber(String teamKey) {
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
     * <p>A relay with no kind is undivided and reports no teams — that is a normal,
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
        if (event.getRelayTeamKind() == null) {
            // Undivided: no kind, so no teams, and none are invented here.
            return RelayEventTeamsDTO.of(event, List.of());
        }
        return RelayEventTeamsDTO.of(event, describe(event, teams));
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
        if (event.getSex() == null || event.getGrade() == null) {
            throw new IllegalStateException(event.getName() + " has no division or grade, so its "
                    + "teams cannot be derived from the register.");
        }

        List<Student> candidates =
                studentRepository.findActiveBySexAndGrade(event.getSex(), event.getGrade());

        // The teams the roster calls for: one key per form, or per house, that the
        // event's own grade and division actually contains.
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
            String label = kind.labelFor(key);
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
                if (!label.equals(team.getLabel())) {
                    team.setLabel(label);
                    relayTeamRepository.save(team);
                }
                kept++;
            }
        }

        // Teams the roster no longer calls for. One with runners is kept whatever the
        // caller asked: dropping it would throw away somebody's selection.
        Set<Long> teamsWithRunners = teamIdsWithRunners(eventId);
        int pruned = 0;
        int keptWithRunners = 0;
        for (RelayTeam team : existing) {
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
        log.info("Derived {} relay teams for event {} ({} {}): {} created, {} kept, {} pruned, "
                        + "{} kept with runners",
                after.size(), event.getId(), event.getName(), kind, created, kept, pruned,
                keptWithRunners);

        return RelayTeamDerivationDTO.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .kind(kind.name())
                .created(created)
                .kept(kept)
                .pruned(pruned)
                .keptWithRunners(keptWithRunners)
                .eligibleStudents(candidates.size())
                .board(RelayEventTeamsDTO.of(event, describe(event, after)))
                .build();
    }

    /** The form number or the house name a student would run under, or null. */
    private static String teamKeyOf(RelayTeamKind kind, Student student) {
        if (kind == RelayTeamKind.FORM) {
            return formKeyOf(student.getClassName());
        }
        String house = student.getHouse();
        return house == null || house.isBlank() ? null : house.trim();
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

        // 2-4. On this year's list, in the event's division and grade, and in this
        //      team's form or house.
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
     * Removes every relay team of an event, with their runners — what deleting an
     * event needs before it can go, and what an administrator uses to start the
     * selection again.
     *
     * @return how many teams were removed
     */
    @Transactional
    public int removeTeamsForEvent(Long eventId) {
        List<RelayTeam> teams = relayTeamRepository.findByEventIdOrderByIdAsc(eventId);
        if (teams.isEmpty()) {
            return 0;
        }
        for (RelayTeam team : teams) {
            relayTeamMemberRepository.deleteByTeamId(team.getId());
        }
        relayTeamMemberRepository.flush();
        relayTeamRepository.deleteAll(teams);
        relayTeamRepository.flush();
        log.info("Removed {} relay team(s) of event {}", teams.size(), eventId);
        return teams.size();
    }

    // ============================================================ the rule book

    /**
     * Whether a student may run in a given team, in one place: the event's division
     * and grade (the same rule entry uses), then the team's own house or form.
     */
    private void requireEligibleForTeam(RelayTeam team, Event event, Student student) {
        if (!Boolean.TRUE.equals(student.getEnabled())) {
            throw new IllegalStateException(student.getName() + " is locked because they are not "
                    + "on this year's student list, so they cannot be named in a relay team.");
        }
        // Division and grade — the rule entries are judged by. It is restated here
        // (with the athlete named, rather than addressed as "you") only because the
        // original is private inside EnrollmentService; the semantics are identical,
        // and the two must be changed together.
        requireEventIsForTheStudent(student, event);

        if (team.getKind() == RelayTeamKind.HOUSE) {
            String house = student.getHouse() == null ? "" : student.getHouse().trim();
            if (house.isEmpty() || !house.equalsIgnoreCase(String.valueOf(team.getTeamKey()).trim())) {
                throw new IllegalStateException(student.getName() + " is "
                        + (house.isEmpty() ? "not in a house" : "in " + house + " House")
                        + ", so they cannot run for " + team.getLabel() + " in " + event.getName()
                        + ".");
            }
            return;
        }

        String form = formKeyOf(student.getClassName());
        if (form == null || !form.equals(String.valueOf(team.getTeamKey()).trim())) {
            throw new IllegalStateException(student.getName() + " is in class "
                    + student.getClassName() + " ("
                    + (form == null ? "no form" : "Form " + form) + "), so they cannot run for "
                    + team.getLabel() + " in " + event.getName() + ".");
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
        if (student.getGrade() != null && event.getGrade() != null
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

    private List<RelayTeamDTO> describe(Event event, List<RelayTeam> teams) {
        List<RelayTeam> ordered = new ArrayList<>(teams);
        ordered.sort(TEAM_ORDER);

        List<RelayTeamMember> all =
                relayTeamMemberRepository.findForEventWithUser(event.getId());
        Map<Long, List<RelayTeamMember>> byTeam = new LinkedHashMap<>();
        for (RelayTeamMember member : all) {
            Long teamId = member.getTeam() == null ? null : member.getTeam().getId();
            byTeam.computeIfAbsent(teamId, key -> new ArrayList<>()).add(member);
        }
        Map<Long, Student> rosters = rostersFor(all);

        List<RelayTeamDTO> described = new ArrayList<>(ordered.size());
        for (RelayTeam team : ordered) {
            described.add(RelayTeamDTO.from(team,
                    byTeam.getOrDefault(team.getId(), List.of()), rosters));
        }
        return described;
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
