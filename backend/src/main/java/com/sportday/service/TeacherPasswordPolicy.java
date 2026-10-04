package com.sportday.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The password rule for a <strong>teacher</strong> account.
 *
 * <p>It is deliberately <em>not</em> the student rule. A student's password is
 * derived from their own record — {@code yyyyMMdd(dob) + class + class number} —
 * because a student knows their date of birth and the office can hand the sheet
 * out. A teacher has no date of birth on file, and reusing a personal detail as
 * a staff credential would be the wrong habit to build into the system, so a
 * teacher's password is derived from something else entirely.</p>
 *
 * <h2>The rule</h2>
 * <pre>
 *   password = "SD" + first 8 hex digits of SHA-256("teacher:" + username)
 *   e.g. username teasmith  -&gt;  SD1F4C0A9B      (10 characters)
 * </pre>
 *
 * <p>Three properties matter, and they are the same three the student rule is
 * built on:</p>
 *
 * <ul>
 *   <li><strong>Deterministic.</strong> The same username always produces the
 *       same password, so the credentials sheet can be regenerated at any time
 *       and never drifts out of sync with the account — which is what makes the
 *       bulk upload idempotent.</li>
 *   <li><strong>Not guessable from the register.</strong> It is a hash, so it
 *       does not read as "the teacher's own details plus a counter". Two
 *       teachers with adjacent usernames get unrelated-looking passwords.</li>
 *   <li><strong>Overridable.</strong> An administrator who supplies a
 *       {@code password} column gets exactly that password instead. Providing
 *       one re-upload is how a forgotten password is reset; omitting the column
 *       restores the derived one, so a re-upload always leaves a known
 *       credential behind rather than a secret nobody holds.</li>
 * </ul>
 *
 * <p>Nothing here is stored in clear text: only the BCrypt hash of the result
 * reaches the database, exactly as for a student.</p>
 */
@Component
public class TeacherPasswordPolicy {

    /** Prefix so a teacher credential is recognisable as one at a glance. */
    public static final String PREFIX = "SD";

    /** How many hex characters of the digest are kept. */
    public static final int DIGEST_CHARS = 8;

    /** The shortest password an administrator may supply in the upload. */
    public static final int MIN_SUPPLIED_LENGTH = 6;

    public static final String DESCRIPTION =
            "SD + the first 8 hex digits of SHA-256(\"teacher:\" + username), e.g. SD1F4C0A9B. "
                    + "Supplying a password column overrides it; omitting the column restores the derived one.";

    /**
     * The password for this teacher. Never stored in clear text — only its BCrypt
     * hash is.
     */
    public String generate(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("a username is required to generate a teacher password");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("teacher:" + username.trim().toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return PREFIX + hex.substring(0, DIGEST_CHARS).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JVM, so this cannot happen in practice.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** How the rule is described to administrators, in the upload response and the docs. */
    public String describeRule() {
        return DESCRIPTION;
    }
}
