package com.sportday.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a TEACHER can and cannot reach.
 *
 * <p>This asserts the <em>role wiring</em> rather than the service logic, and it
 * does so without a Spring context. The version this replaces built a filter chain
 * by hand in an {@code AnnotationConfigApplicationContext} - registering Spring
 * Security's own package-private {@code HttpSecurityConfiguration} by name - and
 * pushed requests through it. That harness never sees the controllers, so it can
 * only ever prove the request rules and never the {@code @PreAuthorize} on a
 * controller method; a context that has to be assembled by name is also the part
 * that broke when the framework moved on. A reflection test that compiles and is
 * deterministic beats a context test that is not.</p>
 *
 * <h2>What is read, and from where</h2>
 * There are two places a role can be required, and both are read off the product
 * itself rather than restated here:
 * <ul>
 *   <li><strong>Method security</strong> - the {@code @PreAuthorize} on each
 *       controller class and method, read by reflection over the classes Spring
 *       would route to (found by scanning {@code com.sportday.controller} for
 *       {@code @RestController}). Every role named comes out of the annotation's
 *       own text, never from a list copied into this test.</li>
 *   <li><strong>Request rules</strong> - the {@code requestMatchers(...)} /
 *       {@code anyRequest()} chain of {@code SecurityConfig.java}, read as the
 *       ordered rules it declares. This is the simplest reliable reader available
 *       without a context: {@code SecurityFilterChain} exposes no {@code doFilter}
 *       (the method the deleted duplicate tried to call), and building a real
 *       chain needs exactly the Spring context this test exists to avoid. Spring's
 *       own {@link AntPathMatcher} does the path matching, so the only thing this
 *       test implements itself is the trivial decision
 *       "permitAll / authenticated / hasRole". The file is read at test time, so a
 *       rule change is picked up automatically and a rule this test does not
 *       understand fails it rather than being assumed safe.</li>
 * </ul>
 *
 * <p>A call is permitted only when <em>both</em> layers admit it, which is what
 * the container does: method security is checked after the filter chain.</p>
 */
class TeacherSecurityWiringTest {

    /** Spring's matcher, and the one the request rules are matched with at runtime. */
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    // The readers below are used by the two fields that follow, so they are declared
    // first: static fields are initialised in the order they are written.
    private static final Pattern REQUEST_RULE = Pattern.compile(
            "\\.(requestMatchers|anyRequest)\\(([^)]*)\\)\\s*\\.\\s*(\\w+)\\(([^)]*)\\)");

    private static final Pattern ROLE_CALL = Pattern.compile("has(?:Any)?Role\\s*\\(([^)]*)\\)");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"|'([^']*)'");

    private static final Pattern HTTP_METHOD = Pattern.compile("HttpMethod\\.([A-Z]+)");

    /** The endpoints the application exposes, read off the controller classes. */
    private static final List<Endpoint> ENDPOINTS = scanEndpoints();

    /** The request rules {@code SecurityConfig} declares, in the order it declares them. */
    private static final List<RequestRule> REQUEST_RULES = readRequestRules();

    // -------------------------------------------------------------- endpoints

    /** One controller endpoint: its verb, its path pattern, and the rules it carries. */
    private record Endpoint(String httpMethod, String path, List<String> expressions) {

        boolean matches(String method, String path) {
            boolean verb = "*".equals(httpMethod) || httpMethod.equals(method);
            return verb && PATHS.match(this.path, path);
        }

        boolean isRead() {
            return "*".equals(httpMethod) || "GET".equals(httpMethod) || "HEAD".equals(httpMethod);
        }

        boolean isWrite() {
            return !isRead();
        }

        boolean namesRole(String role) {
            return rolesNamed().contains(role);
        }

        Set<String> rolesNamed() {
            Set<String> roles = new LinkedHashSet<>();
            for (String expression : expressions) {
                roles.addAll(rolesNamedIn(expression));
            }
            return roles;
        }

        boolean isPublic() {
            return expressions.stream().anyMatch(expression -> expression.contains("permitAll"));
        }

        /**
         * The expressions that would let a caller in on the strength of being signed
         * in alone - a bare {@code isAuthenticated()} with no role named.
         */
        List<String> bareAuthenticationChecks() {
            return expressions.stream()
                    .filter(expression -> expression.contains("isAuthenticated")
                            && rolesNamedIn(expression).isEmpty())
                    .toList();
        }

        @Override
        public String toString() {
            return httpMethod + " " + path + " " + expressions;
        }
    }

    private static List<Endpoint> scanEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Endpoint> endpoints = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.sportday.controller")) {
            Class<?> controller = load(definition.getBeanClassName());
            List<String> basePaths = mappingPaths(controller);
            List<String> classRules = preAuthorize(controller.getDeclaredAnnotations());
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                List<String> rules = new ArrayList<>(classRules);
                rules.addAll(preAuthorize(method.getDeclaredAnnotations()));
                List<String> paths = mappingPaths(mapping);
                for (String base : basePaths.isEmpty() ? List.of("") : basePaths) {
                    for (String path : paths) {
                        for (String verb : verbsOf(mapping)) {
                            endpoints.add(new Endpoint(verb, join(base, path), List.copyOf(rules)));
                        }
                    }
                }
            }
        }
        assertFalse(endpoints.isEmpty(),
                "no controllers were found under com.sportday.controller, so nothing was proved");
        return List.copyOf(endpoints);
    }

    private static List<String> mappingPaths(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        return mapping == null ? List.of() : mappingPaths(mapping);
    }

    private static List<String> mappingPaths(RequestMapping mapping) {
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return paths.length == 0 ? List.of("") : List.of(paths);
    }

    private static List<String> verbsOf(RequestMapping mapping) {
        RequestMethod[] methods = mapping.method();
        if (methods.length == 0) {
            // A mapping with no verb answers every method.
            return List.of("*");
        }
        return Arrays.stream(methods).map(Enum::name).toList();
    }

    private static List<String> preAuthorize(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof PreAuthorize preAuthorize) {
                return List.of(preAuthorize.value());
            }
        }
        return List.of();
    }

    private static String join(String base, String path) {
        String left = base == null ? "" : base.trim();
        String right = path == null ? "" : path.trim();
        if (left.isEmpty()) {
            return right.isEmpty() ? "/" : right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return (left.endsWith("/") ? left.substring(0, left.length() - 1) : left)
                + (right.startsWith("/") ? right : "/" + right);
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Could not load the controller " + className, ex);
        }
    }

    /** The roles a {@code @PreAuthorize} expression names, read out of the expression itself. */
    private static Set<String> rolesNamedIn(String expression) {
        Set<String> roles = new LinkedHashSet<>();
        Matcher call = ROLE_CALL.matcher(expression);
        while (call.find()) {
            roles.addAll(stringLiterals(call.group(1)));
        }
        return roles;
    }

    private static List<String> stringLiterals(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return values;
    }

    // ---------------------------------------------------------- request rules

    /** One rule of the {@code authorizeHttpRequests} chain, as declared. */
    private record RequestRule(String httpMethod, List<String> patterns, String access, Set<String> roles) {

        boolean matches(String method, String path) {
            if (httpMethod != null && !httpMethod.equals(method)) {
                return false;
            }
            return patterns.stream().anyMatch(pattern -> PATHS.match(pattern, path));
        }

        boolean admits(String role) {
            return switch (access) {
                case "permitAll" -> true;
                case "authenticated" -> role != null;
                case "hasRole", "hasAnyRole" -> role != null && roles.contains(role);
                default -> throw new IllegalStateException(
                        "The request rule '" + this + "' is not one this test can decide; "
                                + "teach it the case rather than assuming it lets anybody through");
            };
        }

        @Override
        public String toString() {
            return (httpMethod == null ? "" : httpMethod + " ") + patterns
                    + " -> " + access + roles;
        }
    }

    private static List<RequestRule> readRequestRules() {
        String source = securityConfigSource();
        int start = source.indexOf(".authorizeHttpRequests(");
        int end = source.indexOf(".addFilterBefore(", start);
        if (start < 0 || end < start) {
            throw new IllegalStateException(
                    "SecurityConfig no longer declares an authorizeHttpRequests(...) block closed by "
                            + ".addFilterBefore(...), which is where its request rules are read from");
        }
        // A comment may describe a rule; only the code counts.
        String block = source.substring(start, end).replaceAll("(?m)//.*$", "");
        Matcher matcher = REQUEST_RULE.matcher(block);
        List<RequestRule> rules = new ArrayList<>();
        while (matcher.find()) {
            boolean anyRequest = "anyRequest".equals(matcher.group(1));
            String arguments = matcher.group(2);
            rules.add(new RequestRule(
                    anyRequest ? null : httpMethodIn(arguments),
                    anyRequest ? List.of("/**") : List.copyOf(stringLiterals(arguments)),
                    matcher.group(3),
                    Set.copyOf(stringLiterals(matcher.group(4)))));
        }
        if (rules.isEmpty()) {
            throw new IllegalStateException("No request rules could be read from SecurityConfig");
        }
        return List.copyOf(rules);
    }

    private static String httpMethodIn(String arguments) {
        Matcher matcher = HTTP_METHOD.matcher(arguments);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The declared rules live in the source of the configuration that declares them. */
    private static String securityConfigSource() {
        Path relative = Path.of("src", "main", "java", "com", "sportday", "config", "SecurityConfig.java");
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }
        throw new IllegalStateException(
                "SecurityConfig.java was not found above " + Path.of("").toAbsolutePath());
    }

    // ----------------------------------------------------------- the decision

    /**
     * Whether a caller holding {@code role} (or nobody at all when it is null) gets
     * past both layers for one call. Every controller endpoint the path maps to
     * must allow the caller: a call is not "permitted" because one of two matching
     * mappings happened to allow it.
     */
    private static boolean permitted(String httpMethod, String path, String role) {
        List<Endpoint> matches = matchingEndpoints(httpMethod, path);
        if (matches.isEmpty()) {
            throw new IllegalStateException("No controller endpoint serves " + httpMethod + " " + path
                    + ", so the role wiring cannot be proved about it");
        }
        for (Endpoint endpoint : matches) {
            for (String expression : endpoint.expressions()) {
                if (!expressionAdmits(expression, role)) {
                    return false;
                }
            }
        }
        RequestRule rule = firstMatchingRule(httpMethod, path);
        return rule != null && rule.admits(role);
    }

    private static List<Endpoint> matchingEndpoints(String httpMethod, String path) {
        List<Endpoint> matches = ENDPOINTS.stream().filter(endpoint -> endpoint.matches(httpMethod, path)).toList();
        if (matches.size() <= 1) {
            return matches;
        }
        // Spring routes a request to the single most specific matching pattern - an
        // exact path like /api/users/me wins over /api/users/{id} - so that is the
        // mapping whose rules apply. Patterns of equal specificity are all kept, so
        // a call is never called permitted because one of two equal mappings allows
        // it. The comparison is Spring's own.
        Comparator<String> specificity = PATHS.getPatternComparator(path);
        List<Endpoint> mostSpecific = new ArrayList<>();
        String best = null;
        for (Endpoint endpoint : matches) {
            int against = best == null ? -1 : specificity.compare(endpoint.path(), best);
            if (against < 0) {
                best = endpoint.path();
                mostSpecific.clear();
                mostSpecific.add(endpoint);
            } else if (against == 0) {
                mostSpecific.add(endpoint);
            }
        }
        return List.copyOf(mostSpecific);
    }

    private static RequestRule firstMatchingRule(String httpMethod, String path) {
        for (RequestRule rule : REQUEST_RULES) {
            if (rule.matches(httpMethod, path)) {
                return rule;
            }
        }
        return null;
    }

    private static boolean expressionAdmits(String expression, String role) {
        if (expression.contains("permitAll")) {
            return true;
        }
        if (expression.contains("isAuthenticated") || expression.contains("isFullyAuthenticated")) {
            return role != null;
        }
        Set<String> named = rolesNamedIn(expression);
        if (named.isEmpty()) {
            throw new IllegalStateException("@PreAuthorize(\"" + expression + "\") is not a rule this "
                    + "test can decide; teach it the case rather than assuming it lets anybody through");
        }
        return role != null && named.contains(role);
    }

    private static List<Endpoint> teacherEndpoints() {
        return ENDPOINTS.stream().filter(endpoint -> endpoint.path().startsWith("/api/teacher")).toList();
    }

    /** The mark-entry grid: read the sheet, save the marks. */
    private static List<Endpoint> markEntryEndpoints() {
        return ENDPOINTS.stream().filter(endpoint -> endpoint.path().endsWith("/marks")).toList();
    }

    /**
     * The marking-sheet PDFs, by exactly the three patterns SecurityConfig guards
     * them with. Kept as patterns rather than a list of paths so a new sheet
     * endpoint is picked up rather than missed.
     */
    private static boolean isMarkingSheet(String path) {
        return PATHS.match("/api/groups/*/sheet.pdf", path)
                || PATHS.match("/api/events/*/sheets.pdf", path)
                || PATHS.match("/api/sheets.pdf", path);
    }

    private static List<Endpoint> markingSheetEndpoints() {
        return ENDPOINTS.stream().filter(endpoint -> isMarkingSheet(endpoint.path())).toList();
    }

    /**
     * The families that change the programme or run the competition: the event
     * CRUD, the heat and final allocation, the draw, the register and the whole of
     * {@code /api/admin/**}, the records and their baselines, the settings write,
     * user management, the season, the backups, the relay teams and the teacher
     * entry family. A helper may reach none of them.
     */
    private static boolean isAdministrative(String path, boolean write) {
        return path.startsWith("/api/admin")
                || path.startsWith("/api/teacher")
                // The programme: creating, editing, enabling, deleting an event, and
                // drawing, allocating or clearing heats and the final. Reading the
                // programme is not on this list — a helper has to read it — and
                // neither is the mark-entry grid, which is the one write a helper is
                // for.
                || (write && !path.endsWith("/marks")
                    && (PATHS.match("/api/events/**", path) || PATHS.match("/api/groups/**", path)))
                // Results recording, the records, the placings and the championships.
                || (write && PATHS.match("/api/results/**", path))
                || (write && path.startsWith("/api/records"))
                || (write && path.contains("settings"))
                || (path.startsWith("/api/users") && !"/api/users/me".equals(path));
    }

    // ------------------------------------------------- what a teacher may reach

    @Test
    @DisplayName("a teacher may reach the endpoints for helping a student enter or withdraw")
    void teacherMayHelpStudents() {
        List<Endpoint> family = teacherEndpoints();
        assertFalse(family.isEmpty(), "the teacher entry family must exist to be proved open");
        for (Endpoint endpoint : family) {
            assertTrue(endpoint.namesRole("TEACHER"),
                    "every teacher endpoint admits a TEACHER: " + endpoint);
            assertTrue(permitted(endpoint.httpMethod(), endpoint.path(), "TEACHER"),
                    "a TEACHER reaches " + endpoint);
        }
        // The calls the family is for, spelled out so one going missing is caught
        // even while the others still look right.
        assertTrue(permitted("GET", "/api/teacher/me", "TEACHER"));
        assertTrue(permitted("GET", "/api/teacher/students", "TEACHER"));
        assertTrue(permitted("GET", "/api/teacher/students/S0001/enrollments", "TEACHER"));
        assertTrue(permitted("POST", "/api/teacher/students/S0001/enrollments/2", "TEACHER"));
        assertTrue(permitted("DELETE", "/api/teacher/students/S0001/enrollments/2", "TEACHER"));
    }

    @Test
    @DisplayName("a teacher may read the programme, the settings and their own profile")
    void teacherMayReadWhatHelpingNeeds() {
        // None of these carries a role of its own: they are opened to a signed-in
        // caller by the request rules, which is what admits a teacher here.
        for (String path : List.of("/api/events", "/api/events/2", "/api/users/me", "/api/settings")) {
            assertTrue(permitted("GET", path, "TEACHER"), "a teacher may read " + path);
        }
    }

    @Test
    @DisplayName("an administrator reaches the teacher endpoints too, because they may help anybody")
    void adminReachesTheSameEndpoints() {
        long teacherNamed = ENDPOINTS.stream().filter(endpoint -> endpoint.namesRole("TEACHER")).count();
        assertTrue(teacherNamed > 0, "the TEACHER role must appear somewhere, or this proves nothing");
        for (Endpoint endpoint : ENDPOINTS) {
            if (endpoint.namesRole("TEACHER")) {
                assertTrue(endpoint.namesRole("ADMIN"),
                        "every rule that admits a TEACHER admits an ADMIN as well: " + endpoint);
            }
        }
        for (Endpoint endpoint : teacherEndpoints()) {
            assertTrue(endpoint.namesRole("ADMIN"),
                    "an administrator reaches the teacher endpoints: " + endpoint);
            assertTrue(permitted(endpoint.httpMethod(), endpoint.path(), "ADMIN"),
                    "an ADMIN reaches " + endpoint);
        }
        assertTrue(permitted("GET", "/api/teacher/students", "ADMIN"));
        assertTrue(permitted("POST", "/api/teacher/students/S0001/enrollments/2", "ADMIN"));
        assertTrue(permitted("DELETE", "/api/teacher/students/S0001/enrollments/2", "ADMIN"));
    }

    @Test
    @DisplayName("a manager reaches neither: helping students is an administrator's or a teacher's job")
    void aManagerCannotReachTeacherEndpoints() {
        for (Endpoint endpoint : teacherEndpoints()) {
            assertFalse(endpoint.namesRole("MANAGER"),
                    "a teacher endpoint does not name MANAGER: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "MANAGER"),
                    "a MANAGER does not reach " + endpoint);
        }
        assertFalse(permitted("GET", "/api/teacher/students", "MANAGER"));
        assertFalse(permitted("POST", "/api/teacher/students/S0001/enrollments/2", "MANAGER"));
    }

    // --------------------------------------------- what a teacher may NOT reach

    @Test
    @DisplayName("a teacher cannot reach mark entry")
    void teacherCannotEnterMarks() {
        List<Endpoint> marks = ENDPOINTS.stream().filter(endpoint -> endpoint.path().endsWith("/marks")).toList();
        assertFalse(marks.isEmpty(), "the mark-entry endpoints must exist to be proved shut");
        for (Endpoint endpoint : marks) {
            assertFalse(endpoint.namesRole("TEACHER"),
                    "mark entry is staff-only and does not name TEACHER: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "TEACHER"),
                    "a TEACHER does not reach " + endpoint);
        }
        assertFalse(permitted("GET", "/api/events/2/marks", "TEACHER"));
        assertFalse(permitted("POST", "/api/events/2/marks", "TEACHER"));
    }

    @Test
    @DisplayName("a teacher cannot edit the programme, draw heats or print the marking sheets")
    void teacherCannotEditEvents() {
        for (Endpoint endpoint : ENDPOINTS) {
            boolean changesTheProgramme = endpoint.isWrite()
                    && (PATHS.match("/api/events/**", endpoint.path())
                    || PATHS.match("/api/groups/**", endpoint.path()));
            boolean markingSheet = PATHS.match("/api/groups/*/sheet.pdf", endpoint.path())
                    || PATHS.match("/api/events/*/sheets.pdf", endpoint.path())
                    || PATHS.match("/api/sheets.pdf", endpoint.path());
            if (!changesTheProgramme && !markingSheet) {
                continue;
            }
            assertFalse(endpoint.namesRole("TEACHER"),
                    "the programme is not a teacher's to change: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "TEACHER"),
                    "a TEACHER does not reach " + endpoint);
        }
        assertFalse(permitted("POST", "/api/events", "TEACHER"));
        assertFalse(permitted("PUT", "/api/events/2", "TEACHER"));
        assertFalse(permitted("PATCH", "/api/events/2/enable", "TEACHER"));
        assertFalse(permitted("DELETE", "/api/events/2", "TEACHER"));
        assertFalse(permitted("POST", "/api/events/2/groups/allocate", "TEACHER"));
        assertFalse(permitted("POST", "/api/events/2/final", "TEACHER"));
        assertFalse(permitted("GET", "/api/groups/1/sheet.pdf", "TEACHER"));
        assertFalse(permitted("GET", "/api/sheets.pdf", "TEACHER"));
    }

    @Test
    @DisplayName("a teacher cannot reach the teacher upload, the register, settings or user management")
    void teacherCannotReachAdministration() {
        for (Endpoint endpoint : ENDPOINTS) {
            // Every /api/admin/** endpoint, plus the settings endpoints that change
            // the settings - reading the current settings is part of helping (see
            // teacherMayReadWhatHelpingNeeds) but changing them is not.
            boolean administration = endpoint.path().startsWith("/api/admin")
                    || (endpoint.isWrite() && endpoint.path().contains("settings"));
            if (!administration) {
                continue;
            }
            assertFalse(endpoint.namesRole("TEACHER"),
                    "administration does not name TEACHER: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "TEACHER"),
                    "a TEACHER does not reach " + endpoint);
        }
        assertFalse(permitted("POST", "/api/admin/teachers/upload", "TEACHER"));
        assertFalse(permitted("GET", "/api/admin/teachers", "TEACHER"));
        assertFalse(permitted("GET", "/api/admin/teachers/credentials.csv", "TEACHER"));
        assertFalse(permitted("POST", "/api/admin/students/upload", "TEACHER"));
        assertFalse(permitted("GET", "/api/admin/students", "TEACHER"));
        assertFalse(permitted("POST", "/api/admin/students/S0001/enrollments/2", "TEACHER"));
        assertFalse(permitted("DELETE", "/api/admin/students/S0001/enrollments/2", "TEACHER"));
        assertFalse(permitted("GET", "/api/users", "TEACHER"));
        assertFalse(permitted("POST", "/api/admin/users", "TEACHER"));
        assertFalse(permitted("PUT", "/api/admin/settings", "TEACHER"));
        assertFalse(permitted("POST", "/api/admin/season/reset", "TEACHER"));
    }

    // ---------------------------------------------------- who else is kept out

    @Test
    @DisplayName("a student cannot reach the teacher endpoints either")
    void aStudentCannotReachTeacherEndpoints() {
        for (Endpoint endpoint : teacherEndpoints()) {
            assertFalse(endpoint.namesRole("STUDENT"),
                    "a teacher endpoint does not name STUDENT: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "STUDENT"),
                    "a STUDENT does not reach " + endpoint);
        }
        assertFalse(permitted("GET", "/api/teacher/students", "STUDENT"));
        assertFalse(permitted("POST", "/api/teacher/students/S0001/enrollments/2", "STUDENT"));
    }

    @Test
    @DisplayName("the teacher endpoints are not public")
    void teacherEndpointsAreNotPublic() {
        for (Endpoint endpoint : teacherEndpoints()) {
            assertFalse(endpoint.isPublic(), "a teacher endpoint is not permitAll: " + endpoint);
            assertTrue(endpoint.bareAuthenticationChecks().isEmpty(),
                    "a teacher endpoint requires a role, not merely a signed-in caller: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), null),
                    "a signed-out caller does not reach " + endpoint);
        }
        assertFalse(permitted("GET", "/api/teacher/students", null));
        assertFalse(permitted("POST", "/api/teacher/students/S0001/enrollments/2", null));

        // The request rule on its own, asserted separately: whatever the controllers
        // say, the request path is a two-role rule and nothing wider.
        RequestRule teacherRule = firstMatchingRule("GET", "/api/teacher/students");
        assertNotNull(teacherRule, "SecurityConfig must have a rule covering /api/teacher/**");
        assertEquals(List.of("/api/teacher/**"), teacherRule.patterns(),
                "the teacher family is guarded by one rule over the whole path");
        assertEquals("hasAnyRole", teacherRule.access(),
                "a teacher endpoint is not open and not merely authenticated");
        assertEquals(Set.of("ADMIN", "TEACHER"), teacherRule.roles(),
                "an administrator and a teacher, and nobody else: " + teacherRule);
    }

    // ---------------------------------------------- the rules that were there

    @Test
    @DisplayName("the endpoints that were already open stay open, and the admin-only ones stay admin-only")
    void theExistingRulesAreUnchanged() {
        assertTrue(permitted("GET", "/api/events", null), "the programme is public");
        assertTrue(permitted("POST", "/api/auth/login", null), "sign-in is public");
        assertTrue(permitted("GET", "/api/events/2/marks", "MANAGER"), "managers still enter marks");
        assertTrue(permitted("GET", "/api/events/2/marks", "ADMIN"));
        assertFalse(permitted("GET", "/api/events/2/marks", "USER"),
                "mark entry is not for a student's own login");
        assertTrue(permitted("GET", "/api/admin/teachers/credentials.csv", "ADMIN"));
        assertTrue(permitted("GET", "/api/users", "MANAGER"));
        assertFalse(permitted("GET", "/api/users", "STUDENT"),
                "the user list is not for a student's own login");
    }

    // ------------------------------------------------ what a HELPER may reach
    //
    // An input helper is a volunteer on the day: they key in marks and print the
    // marking sheets, and nothing else. These tests are the other half of the same
    // mechanism the teacher tests above use — the controllers' own
    // @PreAuthorize text and SecurityConfig's own request rules, read off the
    // product — rather than a second way of deciding the same question.
    //
    // The trap they exist for: SecurityConfig's public `GET /api/events/**` rule
    // matches `GET /api/events/{id}/sheets.pdf` before the sheet rule reaches it,
    // so on the URL layer that download looks open to the world. Method security
    // runs second and is what actually closes it, which is why both layers have to
    // name HELPER for a helper to get the sheets at all.

    @Test
    @DisplayName("a helper may read a mark sheet and save the marks")
    void aHelperMayEnterMarks() {
        List<Endpoint> marks = markEntryEndpoints();
        assertFalse(marks.isEmpty(), "the mark-entry endpoints must exist to be proved open");
        for (Endpoint endpoint : marks) {
            assertTrue(endpoint.namesRole("HELPER"),
                    "mark entry is what a helper is for, so it names HELPER: " + endpoint);
            assertTrue(permitted(endpoint.httpMethod(), endpoint.path(), "HELPER"),
                    "a HELPER reaches " + endpoint);
        }
        assertTrue(permitted("GET", "/api/events/2/marks", "HELPER"), "a helper reads the mark sheet");
        assertTrue(permitted("POST", "/api/events/2/marks", "HELPER"), "and saves the marks");
        // The stage is a query parameter on the same mapping, so the final's grid is
        // reached through the path already asserted above; that a final cannot be
        // worked on before it is drawn is the service's rule, tested in
        // MarkEntryFinalStageTest.
    }

    @Test
    @DisplayName("a helper may print the marking sheets, every one of the three endpoints")
    void aHelperMayPrintTheSheets() {
        List<Endpoint> sheets = markingSheetEndpoints();
        assertFalse(sheets.isEmpty(), "the sheet endpoints must exist to be proved open");
        for (Endpoint endpoint : sheets) {
            assertTrue(endpoint.namesRole("HELPER"),
                    "printing the sheets is what a helper is for: " + endpoint);
            assertTrue(permitted(endpoint.httpMethod(), endpoint.path(), "HELPER"),
                    "a HELPER reaches " + endpoint);
        }
        assertTrue(permitted("GET", "/api/groups/1/sheet.pdf", "HELPER"), "one group's sheet");
        assertTrue(permitted("GET", "/api/events/2/sheets.pdf", "HELPER"), "all of an event's sheets");
        assertTrue(permitted("GET", "/api/sheets.pdf", "HELPER"), "and the whole-school print run");
    }

    @Test
    @DisplayName("a helper may read the programme and the settings, which the sheets and the grid need")
    void aHelperMayReadWhatTheJobNeeds() {
        // None of these carries a role of its own; they are opened to a signed-in
        // caller by the request rules, which is what admits a helper.
        for (String path : List.of("/api/events", "/api/events/2", "/api/events/2/groups",
                "/api/groups/1", "/api/settings", "/api/users/me")) {
            assertTrue(permitted("GET", path, "HELPER"), "a helper may read " + path);
        }
    }

    @Test
    @DisplayName("the sheet and mark families are the only two a helper's role appears in")
    void aHelperIsAdmittedToTwoFamiliesAndNoMore() {
        // Asserted over every controller endpoint there is, so a new @PreAuthorize
        // that quietly names HELPER — or a new endpoint dropped inside one of the
        // two families — is caught rather than assumed harmless.
        for (Endpoint endpoint : ENDPOINTS) {
            if (!endpoint.namesRole("HELPER")) {
                continue;
            }
            boolean markEntry = endpoint.path().endsWith("/marks");
            boolean sheet = isMarkingSheet(endpoint.path());
            assertTrue(markEntry || sheet,
                    "HELPER may only be named on mark entry and the marking sheets, "
                            + "but this endpoint names it too: " + endpoint);
        }
        long named = ENDPOINTS.stream().filter(endpoint -> endpoint.namesRole("HELPER")).count();
        assertTrue(named >= 5, "the HELPER role must appear on the mark and sheet families: " + named);
    }

    // ------------------------------------------- what a helper may NOT reach

    @Test
    @DisplayName("a helper cannot change the programme, draw the final or record results")
    void aHelperCannotChangeTheProgramme() {
        for (Endpoint endpoint : ENDPOINTS) {
            if (!isAdministrative(endpoint.path(), endpoint.isWrite())) {
                continue;
            }
            assertFalse(endpoint.namesRole("HELPER"),
                    "a helper fills the programme in, they do not change it: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "HELPER"),
                    "a HELPER does not reach " + endpoint);
        }
        assertFalse(permitted("POST", "/api/events", "HELPER"), "no creating an event");
        assertFalse(permitted("PUT", "/api/events/2", "HELPER"), "no editing one");
        assertFalse(permitted("PATCH", "/api/events/2/enable", "HELPER"), "no enabling or disabling one");
        assertFalse(permitted("DELETE", "/api/events/2", "HELPER"), "no deleting one");
        assertFalse(permitted("POST", "/api/events/defaults", "HELPER"), "and no reseeding the catalogue");
        assertFalse(permitted("POST", "/api/events/2/groups/allocate", "HELPER"), "no allocating heats");
        assertFalse(permitted("DELETE", "/api/events/2/groups", "HELPER"), "no clearing them");
        assertFalse(permitted("POST", "/api/events/2/final", "HELPER"), "no drawing the final");
        assertFalse(permitted("DELETE", "/api/events/2/final", "HELPER"), "and no removing it");
        assertFalse(permitted("POST", "/api/results", "HELPER"), "no recording results");
        assertFalse(permitted("DELETE", "/api/results/3", "HELPER"), "and no removing one");
        // The placings are not closed to a helper — and cannot be, since the
        // public `GET /api/events/**` rule opens /api/events/{id}/standings to
        // everyone. That is reading a result, not recording one: the endpoint that
        // does record them is the POST above, which a helper is refused. Asserted
        // the way round it really is, rather than quietly left out.
        assertTrue(permitted("GET", "/api/events/2/standings", "HELPER"),
                "the placings are public, so a helper can read them like anybody else");
    }

    @Test
    @DisplayName("a helper cannot reach the register, the upload, settings, users, backups or a reset")
    void aHelperCannotReachAdministration() {
        for (Endpoint endpoint : ENDPOINTS) {
            if (!endpoint.path().startsWith("/api/admin")) {
                continue;
            }
            assertFalse(endpoint.namesRole("HELPER"),
                    "administration does not name HELPER: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "HELPER"),
                    "a HELPER does not reach " + endpoint);
        }
        assertFalse(permitted("POST", "/api/admin/students/upload", "HELPER"), "no register upload");
        assertFalse(permitted("POST", "/api/admin/students/upload/roster", "HELPER"), "not even a rehearsal");
        assertFalse(permitted("GET", "/api/admin/students", "HELPER"), "no student list");
        assertFalse(permitted("GET", "/api/admin/students/credentials.csv", "HELPER"), "no credentials");
        assertFalse(permitted("POST", "/api/admin/students/S0001/enrollments/2", "HELPER"),
                "no entering a student for them");
        assertFalse(permitted("POST", "/api/admin/teachers/upload", "HELPER"), "no teacher upload");
        assertFalse(permitted("GET", "/api/admin/teachers", "HELPER"));
        assertFalse(permitted("POST", "/api/admin/users", "HELPER"), "no creating staff accounts");
        assertFalse(permitted("GET", "/api/admin/users/roles", "HELPER"));
        assertFalse(permitted("PUT", "/api/admin/settings", "HELPER"), "no settings");
        assertFalse(permitted("POST", "/api/admin/settings/reset", "HELPER"));
        assertFalse(permitted("POST", "/api/admin/season/reset", "HELPER"), "no season reset");
        assertFalse(permitted("GET", "/api/admin/backups", "HELPER"), "no backups");
        assertFalse(permitted("POST", "/api/admin/backups/x.json/restore", "HELPER"), "no restoring one");
        assertFalse(permitted("POST", "/api/admin/events/2/relay-teams/derive", "HELPER"), "no relay teams");
        assertFalse(permitted("POST", "/api/admin/records/seed", "HELPER"), "no school records");
        assertFalse(permitted("GET", "/api/users", "HELPER"), "no staff account management");
        assertFalse(permitted("DELETE", "/api/users/3", "HELPER"));
    }

    @Test
    @DisplayName("a helper cannot reach the teacher family, and a teacher cannot reach the mark family")
    void aHelperIsNotATeacher() {
        for (Endpoint endpoint : teacherEndpoints()) {
            assertFalse(endpoint.namesRole("HELPER"),
                    "helping a student is a teacher's job, not a helper's: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "HELPER"),
                    "a HELPER does not reach " + endpoint);
        }
        assertFalse(permitted("GET", "/api/teacher/students", "HELPER"));
        assertFalse(permitted("POST", "/api/teacher/students/S0001/enrollments/2", "HELPER"));
        // And the other way round, so the two roles share no endpoint family.
        for (Endpoint endpoint : markEntryEndpoints()) {
            assertFalse(endpoint.namesRole("TEACHER"),
                    "a teacher may not key in marks: " + endpoint);
        }
    }

    @Test
    @DisplayName("a helper is not a manager or an administrator either")
    void aHelperIsNotAManager() {
        for (Endpoint endpoint : markingSheetEndpoints()) {
            assertTrue(endpoint.namesRole("MANAGER") && endpoint.namesRole("ADMIN"),
                    "a helper is added to the sheet family, it does not replace anybody: " + endpoint);
        }
        for (Endpoint endpoint : markEntryEndpoints()) {
            assertTrue(endpoint.namesRole("MANAGER") && endpoint.namesRole("ADMIN"),
                    "and the same on the mark-entry family: " + endpoint);
        }
        assertTrue(permitted("GET", "/api/events/2/marks", "MANAGER"), "managers still enter marks");
        assertTrue(permitted("GET", "/api/events/2/marks", "ADMIN"));
        assertTrue(permitted("GET", "/api/groups/1/sheet.pdf", "MANAGER"), "and still print the sheets");
        assertTrue(permitted("GET", "/api/sheets.pdf", "ADMIN"));
    }

    // ---------------------------------------- the public rule and its trap

    @Test
    @DisplayName("no permitAll rule lets a helper's own endpoints through unauthenticated")
    void noPublicRuleCoversTheMarkAndSheetEndpoints() {
        // This is the trap a previous reader of this file found: an earlier
        // `GET /api/events/**` permitAll rule DOES match `GET /api/events/{id}/sheets.pdf`
        // and `GET /api/events/{id}/marks` on the URL layer, so the guard is the
        // controller annotation and not the request rule. Both layers are asserted
        // here so neither can be dropped on its own.
        RequestRule eventSheetsRule = firstMatchingRule("GET", "/api/events/2/sheets.pdf");
        assertNotNull(eventSheetsRule, "SecurityConfig must have a rule covering the sheet path");
        assertEquals("permitAll", eventSheetsRule.access(),
                "the URL layer really is open here — the controller annotation is what closes it: "
                        + eventSheetsRule);
        Endpoint eventSheets = matchingEndpoints("GET", "/api/events/2/sheets.pdf").get(0);
        assertTrue(eventSheets.namesRole("HELPER"),
                "so the annotation has to name HELPER, or a helper is refused: " + eventSheets);
        assertFalse(permitted("GET", "/api/events/2/sheets.pdf", null),
                "and a signed-out caller is still refused, by the annotation");

        // The mark family is guarded twice over — a rule of its own before the
        // public one, and the class-level annotation.
        RequestRule marksRule = firstMatchingRule("GET", "/api/events/2/marks");
        assertNotNull(marksRule);
        assertEquals("hasAnyRole", marksRule.access(), "mark entry is not a public path: " + marksRule);
        assertEquals(Set.of("ADMIN", "MANAGER", "HELPER"), marksRule.roles(),
                "and the rule names the three roles that may key in marks: " + marksRule);
        assertFalse(permitted("GET", "/api/events/2/marks", null), "a signed-out caller cannot");

        // Nothing in either family is public, and none of it is open to a
        // signed-in student either.
        for (Endpoint endpoint : markEntryEndpoints()) {
            assertFalse(endpoint.isPublic(), "a mark-entry endpoint is not permitAll: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), null));
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "STUDENT"),
                    "a student's own login does not enter marks: " + endpoint);
        }
        for (Endpoint endpoint : markingSheetEndpoints()) {
            assertFalse(endpoint.isPublic(), "a sheet endpoint is not permitAll: " + endpoint);
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), null));
            assertFalse(permitted(endpoint.httpMethod(), endpoint.path(), "STUDENT"),
                    "and a student cannot print the sheets: " + endpoint);
        }
    }

    @Test
    @DisplayName("the sheet family's request rule names an administrator, a manager and a helper")
    void theSheetRuleNamesTheRightThree() {
        RequestRule sheetRule = firstMatchingRule("GET", "/api/groups/1/sheet.pdf");
        assertNotNull(sheetRule, "SecurityConfig must have a rule covering the sheet family");
        assertEquals(Set.of("ADMIN", "MANAGER", "HELPER"), sheetRule.roles(),
                "the sheets are printed by an administrator, a manager or an input helper: " + sheetRule);
        assertFalse(sheetRule.roles().contains("TEACHER"), "not by a teacher");
        assertFalse(sheetRule.roles().contains("STUDENT"), "and not by a student");
    }
}
