package com.sportday.service;

import com.sportday.dto.TeacherUploadResultDTO;
import com.sportday.entity.TeacherClass;
import com.sportday.entity.User;
import com.sportday.repository.TeacherClassRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Bulk upload of teacher accounts.
 *
 * <p>Requirement: an administrator can upload teacher accounts. Re-uploading a
 * teacher updates the account and <em>replaces</em> their class list, so the
 * upload is idempotent. A row whose classes are blank is refused, per row,
 * without failing the rest of the file.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeacherUploadTest {

    @Mock private UserRepository userRepository;
    @Mock private TeacherClassRepository teacherClassRepository;
    @Mock private PasswordEncoder passwordEncoder;

    /**
     * The service is built by hand: the importer and the password policy are the
     * real (pure) objects, because the behaviour under test is what they produce
     * together with the repositories.
     */
    private TeacherService service;

    private final TeacherPasswordPolicy passwordPolicy = new TeacherPasswordPolicy();

    @BeforeEach
    void setUp() {
        service = new TeacherService(userRepository, teacherClassRepository,
                new TeacherImportParser(), passwordPolicy, passwordEncoder);

        when(passwordEncoder.encode(any())).thenAnswer(invocation -> "bcrypt(" + invocation.getArgument(0) + ")");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(100L + Math.abs(user.getUsername().hashCode() % 100));
            }
            return user;
        });
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
    }

    private InputStream csv(String body) {
        return new ByteArrayInputStream(("username,name,email,classes,password\r\n" + body)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The classes written for one teacher, picked out by the account the rows
     * belong to.
     *
     * <p>The repository is called once per uploaded row, so looking at
     * {@code captor.getValue()} - the last call - would assert the last teacher's
     * classes whatever the file held. This walks every call and keeps the rows
     * owned by the teacher named, so a test can ask the question it is actually
     * asserting: which classes did <em>this</em> row's teacher get?</p>
     */
    private List<String> savedClassNamesOf(String username) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TeacherClass>> captor = ArgumentCaptor.forClass(List.class);
        verify(teacherClassRepository, atLeastOnce()).saveAll(captor.capture());
        List<String> names = new ArrayList<>();
        for (List<TeacherClass> rows : captor.getAllValues()) {
            for (TeacherClass row : rows) {
                if (row.getUser() != null && username.equals(row.getUser().getUsername())) {
                    names.add(row.getClassName());
                }
            }
        }
        return names;
    }

    // ------------------------------------------------------------- creation

    @Test
    @DisplayName("a teacher list creates one TEACHER account per row with their classes")
    void uploadCreatesTeacherAccounts() {
        TeacherUploadResultDTO result = service.importTeachers(csv(
                "tchan,Chan Tai Man,tchan@school.edu.hk,1A;3B,\r\n"
                        + "wlee,Lee Siu Ming,,2C;4D,\r\n"), "teachers.csv");

        assertEquals(2, result.getTotalRows());
        assertEquals(2, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getFailed());
        assertTrue(result.getErrors().isEmpty());

        ArgumentCaptor<User> users = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(users.capture());
        assertTrue(users.getAllValues().stream().allMatch(u -> u.getRole() == User.Role.TEACHER),
                "every uploaded account is a TEACHER, not a manager or a student");
        assertEquals(List.of("1A", "3B"), savedClassNamesOf("tchan"),
                "the first row's classes belong to the first teacher");
        assertEquals(List.of("2C", "4D"), savedClassNamesOf("wlee"),
                "and the second row's to the second teacher, not to whoever was written last");
    }

    @Test
    @DisplayName("a generated password is handed back so the office can pass it on")
    void generatedPasswordsComeBackInTheResponse() {
        TeacherUploadResultDTO result = service.importTeachers(csv("tchan,Chan Tai Man,,1A;\r\n"),
                "teachers.csv");

        assertEquals(1, result.getCredentials().size());
        TeacherUploadResultDTO.Credential credential = result.getCredentials().get(0);
        assertEquals("tchan", credential.getUsername());
        assertEquals(passwordPolicy.generate("tchan"), credential.getPassword());
        assertFalse(credential.isSupplied());
        assertTrue(credential.getPassword().startsWith(TeacherPasswordPolicy.PREFIX));
        assertFalse(credential.getPassword().matches(".*\\d{8}.*"),
                "a teacher's password is not the student date-of-birth rule");
    }

    @Test
    @DisplayName("a password in the file is used, and handed back as supplied")
    void aSuppliedPasswordIsUsed() {
        TeacherUploadResultDTO result = service.importTeachers(csv("tchan,Chan Tai Man,,1A,secret99\r\n"),
                "teachers.csv");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals("bcrypt(secret99)", saved.getValue().getPassword());
        assertEquals("secret99", result.getCredentials().get(0).getPassword());
        assertTrue(result.getCredentials().get(0).isSupplied());
    }

    @Test
    @DisplayName("the class separators and the whitespace around them do not matter")
    void classesAcceptBothSeparators() {
        service.importTeachers(csv("tchan,Chan Tai Man,,\" 1a , 3b ; 2C \",\r\n"), "teachers.csv");

        assertEquals(List.of("1A", "3B", "2C"), savedClassNamesOf("tchan"),
                "the three classes of the one row, however they were separated");
    }

    // ------------------------------------------------------------- updating

    @Test
    @DisplayName("re-uploading updates the teacher and replaces their class list")
    void reUploadUpdatesAndReplacesClasses() {
        User existing = User.builder()
                .id(55L)
                .username("tchan")
                .password("bcrypt(kept)")
                .fullName("Old Name")
                .role(User.Role.TEACHER)
                .enabled(false)
                .build();
        when(userRepository.findByUsername("tchan")).thenReturn(Optional.of(existing));

        TeacherUploadResultDTO result = service.importTeachers(csv("tchan,Chan Tai Man,,4D;\r\n"),
                "teachers.csv");

        assertEquals(0, result.getCreated());
        assertEquals(1, result.getUpdated());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals("Chan Tai Man", saved.getValue().getFullName());
        assertEquals(User.Role.TEACHER, saved.getValue().getRole());
        assertTrue(saved.getValue().getEnabled(), "a teacher still on the list is active again");
        assertEquals("bcrypt(kept)", saved.getValue().getPassword(),
                "omitting the password column leaves the login alone");

        verify(teacherClassRepository).deleteByUserId(55L);
        assertEquals(List.of("4D"), savedClassNamesOf("tchan"),
                "3B is gone: the list is replaced, not appended to");
    }

    @Test
    @DisplayName("the same file twice creates nothing the second time")
    void theUploadIsIdempotent() {
        User created = User.builder().id(77L).username("tchan").password("x")
                .fullName("Chan Tai Man").role(User.Role.TEACHER).enabled(true).build();
        when(userRepository.findByUsername("tchan")).thenReturn(Optional.of(created));

        TeacherUploadResultDTO second = service.importTeachers(csv("tchan,Chan Tai Man,,1A;3B;\r\n"),
                "teachers.csv");

        assertEquals(0, second.getCreated());
        assertEquals(1, second.getUpdated());
        verify(userRepository).save(any(User.class));
        assertEquals(List.of("1A", "3B"), savedClassNamesOf("tchan"));
    }

    // ---------------------------------------------------------- bad rows

    @Test
    @DisplayName("a row with blank classes is refused without failing the rest of the file")
    void aRowWithNoClassesIsRefused() {
        TeacherUploadResultDTO result = service.importTeachers(csv(
                "tchan,Chan Tai Man,,1A;\r\n"
                        + "wlee,Lee Siu Ming,,;\r\n"
                        + "mho,Ho Ka Yee,,3B;\r\n"), "teachers.csv");

        assertEquals(3, result.getTotalRows());
        assertEquals(2, result.getCreated());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getErrors().size());
        assertEquals(3, result.getErrors().get(0).getRowNumber(), "the row number points at the file");
        assertEquals("wlee", result.getErrors().get(0).getUsername());
        assertTrue(result.getErrors().get(0).getMessage().contains("at least one class"),
                result.getErrors().get(0).getMessage());
        // The good rows around it are still written, each with its own classes.
        assertEquals(List.of("1A"), savedClassNamesOf("tchan"));
        assertEquals(List.of("3B"), savedClassNamesOf("mho"));
    }

    @Test
    @DisplayName("a missing required column fails the whole file with the accepted headings named")
    void aMissingColumnFailsTheFile() {
        // A staff list whose headings name a username and an email but never say
        // which classes each teacher takes: there is nothing to assign, so the
        // file is refused rather than every row being reported as a bad row.
        String withoutClasses = "username,name,email\r\n"
                + "tchan,Chan Tai Man,tchan@school.edu.hk\r\n";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importTeachers(new ByteArrayInputStream(
                        withoutClasses.getBytes(StandardCharsets.UTF_8)), "teachers.csv"));

        assertTrue(error.getMessage().contains("classes"), error.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a duplicate username inside one file is reported, not written twice")
    void aDuplicateUsernameIsReported() {
        TeacherUploadResultDTO result = service.importTeachers(csv(
                "tchan,Chan Tai Man,,1A;\r\n"
                        + "tchan,Tan Tai Man,,3B;\r\n"), "teachers.csv");

        assertEquals(1, result.getCreated());
        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).getMessage().contains("Duplicate username"));
    }

    // ----------------------------------------------------------- rehearsal

    @Test
    @DisplayName("a rehearsal says what would happen and writes nothing")
    void aDryRunWritesNothing() {
        TeacherUploadResultDTO result = service.importTeachers(csv("tchan,Chan Tai Man,,1A;\r\n"),
                "teachers.csv", true);

        assertTrue(result.isDryRun());
        assertEquals(1, result.getCreated());
        assertEquals(0, result.getFailed());
        assertTrue(result.getCredentials().isEmpty(), "a rehearsal generates nothing to hand out");
        verify(userRepository, never()).save(any(User.class));
        verify(teacherClassRepository, never()).saveAll(any());
    }

    // -------------------------------------------------------- credentials

    @Test
    @DisplayName("the credentials sheet names each teacher's classes and their password")
    void credentialsSheetListsTeachers() {
        User teacher = User.builder().id(88L).username("tchan").password("x")
                .fullName("Chan Tai Man").email("tchan@school.edu.hk")
                .role(User.Role.TEACHER).enabled(true).build();
        User student = User.builder().id(89L).username("S0001").password("x")
                .role(User.Role.STUDENT).enabled(true).build();
        when(userRepository.findAll()).thenReturn(List.of(teacher, student));
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(88L)).thenReturn(List.of("1A", "3B"));

        String csv = service.credentialsCsv();

        assertTrue(csv.contains("username,name,email,classes,password"), csv);
        assertTrue(csv.contains("tchan,Chan Tai Man,tchan@school.edu.hk,1A;3B,"
                + passwordPolicy.generate("tchan")), csv);
        assertFalse(csv.contains("S0001"), "a student's login is not on the staff sheet");
    }
}
