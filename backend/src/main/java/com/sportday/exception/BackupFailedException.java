package com.sportday.exception;

/**
 * Thrown when a season backup cannot be written.
 *
 * <p>The reset refuses to run without one, so this exception is what stops it: it
 * is deliberately not caught and swallowed anywhere, and it is thrown
 * <em>before</em> the first delete, so a reset whose backup failed leaves the data
 * exactly as it was.</p>
 *
 * <p>It extends {@link IllegalStateException} so the API answers a conflict with
 * the reason — "the backup could not be written, so nothing was reset" — rather
 * than reporting a server fault.</p>
 */
public class BackupFailedException extends IllegalStateException {

    public BackupFailedException(String message) {
        super(message);
    }

    public BackupFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
