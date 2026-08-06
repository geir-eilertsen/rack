package family.eilertsen.rack.domain.model;

import java.time.Instant;

/**
 * One line of a project's history. Written by the app for the things it did
 * (a status change, a part arriving) and by the user for everything else.
 */
public record ProjectNote(Instant at, String text, String by) {
    public static final String APP = "app";
    public static final String USER = "user";

    public static ProjectNote app(String text) {
        return new ProjectNote(Instant.now(), text, APP);
    }

    public static ProjectNote user(String text) {
        return new ProjectNote(Instant.now(), text, USER);
    }
}
