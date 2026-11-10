package dev.chatledger.ops;

import dev.chatledger.security.SecretFiles;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * {@code java -jar app.jar db-migrate} (task 13, D4): runs the Flyway migrations as the schema owner and exits. It is the only process
 * on the deployment that holds the owner's login; the web process runs with Flyway off ({@code prod} profile) and never receives it.
 * No Spring context starts.
 *
 * <p>Configuration from the environment only: {@code DB_URL}, {@code MIGRATE_USER} and {@code MIGRATE_PASSWORD} (or
 * {@code MIGRATE_PASSWORD_FILE}). It refuses a placeholder, a URL that is not PostgreSQL, and the application's own login used as the
 * owner. A failure is reported by its class name only: Flyway's messages can quote SQL and values.
 */
public final class MigrateCommand {

    public static final String NAME = "db-migrate";
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int REFUSED = 3;
    public static final int INFRASTRUCTURE = 4;

    /** Runs the migrations; replaced in tests. Returns the version reached and the number applied. */
    public interface Migrator {
        MigrateOutcome migrate(String url, String user, String password);
    }

    public record MigrateOutcome(String version, int applied) {
    }

    private final Map<String, String> env;
    private final Migrator migrator;
    private final PrintStream out;
    private final PrintStream err;

    public MigrateCommand(Map<String, String> env, Migrator migrator, PrintStream out, PrintStream err) {
        this.env = env;
        this.migrator = migrator;
        this.out = out;
        this.err = err;
    }

    public static int main(String[] args) {
        Migrator flyway = (url, user, password) -> {
            MigrateResult r = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
            return new MigrateOutcome(r.targetSchemaVersion == null ? "none" : r.targetSchemaVersion, r.migrationsExecuted);
        };
        return new MigrateCommand(System.getenv(), flyway, System.out, System.err).run(args);
    }

    public int run(String[] args) {
        if (args.length != 1 || !NAME.equals(args[0])) {
            return usage("db-migrate takes no options.");
        }
        Map<String, String> resolved;
        try {
            resolved = SecretFiles.resolve(env, List.of("MIGRATE_PASSWORD"));
        } catch (SecretFiles.Refused e) {
            err.println(e.getMessage());
            return REFUSED;
        }
        for (String name : List.of("DB_URL", "MIGRATE_USER", "MIGRATE_PASSWORD")) {
            String value = resolved.get(name);
            if (value == null || value.isBlank()) {
                return usage(name.equals("MIGRATE_PASSWORD") ? "Set MIGRATE_PASSWORD or MIGRATE_PASSWORD_FILE." : "Set " + name + ".");
            }
            if (value.strip().startsWith("<") && value.strip().endsWith(">")) {
                err.println("Refused: " + name + " still holds a placeholder.");
                return REFUSED;
            }
        }
        if (!resolved.get("DB_URL").startsWith("jdbc:postgresql:")) {
            return usage("DB_URL must be a jdbc:postgresql: URL.");
        }
        if (resolved.get("MIGRATE_USER").equals(resolved.get("DB_USER"))) {
            err.println("Refused: MIGRATE_USER must be the schema owner's login, not the application's.");
            return REFUSED;
        }
        try {
            MigrateOutcome result = migrator.migrate(resolved.get("DB_URL"), resolved.get("MIGRATE_USER"), resolved.get("MIGRATE_PASSWORD"));
            out.println("Migrations applied: " + result.applied() + "; schema version " + result.version() + ".");
            return OK;
        } catch (RuntimeException e) {
            err.println("The migrations failed (" + e.getClass().getSimpleName() + "). Nothing after the failing migration was applied.");
            return INFRASTRUCTURE;
        }
    }

    private int usage(String problem) {
        err.println(problem);
        err.println("Usage: db-migrate   (DB_URL, MIGRATE_USER and MIGRATE_PASSWORD or MIGRATE_PASSWORD_FILE from the environment)");
        return USAGE;
    }
}
