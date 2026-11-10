package dev.chatledger.provisioning;

import dev.chatledger.security.Identifiers;
import dev.chatledger.security.PasswordHashing;
import dev.chatledger.security.PasswordPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator commands that create a workspace ({@code provision-tenant}) and a user in it
 * ({@code provision-user}). There is no web page for either: the running application has no
 * privilege to create tenants or users, and these commands connect as a separate database
 * login that can only execute the two provisioning functions (V013).
 *
 * <p>The new user's password never travels on the command line or in the environment, where
 * it would show up in the process list, shell history or crash dumps. It is typed at a
 * prompt (twice, no echo) or, when there is no terminal, read from one line of standard input.
 * Any attempt to pass it another way is refused, not ignored.
 *
 * <p>Exit codes: 0 done, 2 usage or validation problem, 3 refused (a forbidden way of passing
 * a secret, or a placeholder value), 4 database or infrastructure failure.
 *
 * <p>No Spring context is started, so the command needs only the three provisioning
 * variables ({@code PROVISION_DB_URL}, {@code PROVISION_DB_USER}, {@code PROVISION_DB_PASSWORD}).
 * Output never contains a password, a hash, or the text of a database error (a constraint
 * error can quote the row, hash included).
 *
 * <p>Requirement: 9.1, 9.4. Task 3.6.
 */
public final class ProvisioningCommand {

    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int REFUSED = 3;
    public static final int INFRASTRUCTURE = 4;

    public static final String TENANT = "provision-tenant";
    public static final String USER = "provision-user";

    static final String ENV_URL = "PROVISION_DB_URL";
    static final String ENV_USER = "PROVISION_DB_USER";
    static final String ENV_PASSWORD = "PROVISION_DB_PASSWORD";

    private static final Set<String> USER_ROLES = Set.of("SELLER", "HELPER");

    /** Where the new user's password comes from. Implementations must not echo it. */
    public interface PasswordSource {
        /** @return the password, or empty if none was provided */
        Optional<String> read(boolean confirm) throws IOException;
    }

    /** Opens the database connection; replaced in tests. */
    public interface Connector {
        Connection open(String url, String user, String password) throws SQLException;
    }

    private final Map<String, String> env;
    private final PasswordSource passwords;
    private final Connector connector;
    private final PrintStream out;
    private final PrintStream err;

    public ProvisioningCommand(Map<String, String> env, PasswordSource passwords, Connector connector,
                               PrintStream out, PrintStream err) {
        this.env = env;
        this.passwords = passwords;
        this.connector = connector;
        this.out = out;
        this.err = err;
    }

    /** Production entry point: real environment, terminal or stdin, real JDBC. */
    public static int main(String[] args) {
        return new ProvisioningCommand(System.getenv(), consoleOrStdin(System.in), java.sql.DriverManager::getConnection,
                System.out, System.err).run(args);
    }

    public int run(String[] args) {
        if (args.length == 0 || !(TENANT.equals(args[0]) || USER.equals(args[0]))) {
            return usage("Unknown command.");
        }
        String command = args[0];

        // Refuse forbidden secret channels before anything else is read.
        for (String name : env.keySet()) {
            if (forbiddenEnvironmentName(name)) {
                err.println("Refused: the environment variable " + name + " is set. The new user's password is "
                        + "never read from the environment. Unset it and run the command again.");
                return REFUSED;
            }
        }
        Map<String, String> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                return usage("Unexpected argument.");
            }
            String name = arg.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (forbiddenFlag(name)) {
                // The value is not echoed: it is the thing we are protecting.
                err.println("Refused: passwords and hashes are not accepted as arguments. "
                        + "Type the password at the prompt, or pipe it on standard input.");
                return REFUSED;
            }
            if (!allowedFlags(command).contains(name)) {
                return usage("Unknown option --" + name + ".");
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    return usage("Option --" + name + " needs a value.");
                }
                value = args[++i];
            }
            if (flags.put(name, value) != null) {
                return usage("Option --" + name + " was given twice.");
            }
        }
        for (String required : allowedFlags(command)) {
            if (!flags.containsKey(required)) {
                return usage("Missing option --" + required + ".");
            }
        }

        Optional<String> workspace = Identifiers.workspace(flags.get("workspace"));
        if (workspace.isEmpty()) {
            return usage("The workspace code must be 3 to 32 characters: lower-case letters, digits and hyphens, "
                    + "starting and ending with a letter or digit.");
        }
        Optional<String> email = Identifiers.email(flags.get("email"));
        if (email.isEmpty()) {
            return usage("The email address is not valid.");
        }
        String name = null;
        String role = "SELLER";
        if (TENANT.equals(command)) {
            name = flags.get("name").strip();
            if (name.isEmpty() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
                return usage("The workspace name must be 1 to 100 characters without control characters.");
            }
        } else {
            role = flags.get("role").strip().toUpperCase(Locale.ROOT);
            if (!USER_ROLES.contains(role)) {
                return usage("The role must be SELLER or HELPER.");
            }
        }

        String url = env.get(ENV_URL);
        String dbUser = env.get(ENV_USER);
        String dbPassword = env.get(ENV_PASSWORD);
        for (String[] variable : new String[][]{{ENV_URL, url}, {ENV_USER, dbUser}, {ENV_PASSWORD, dbPassword}}) {
            if (variable[1] == null || variable[1].isBlank()) {
                return usage("Set " + variable[0] + " (see .env.example).");
            }
            if (isPlaceholder(variable[1])) {
                err.println("Refused: " + variable[0] + " still holds a placeholder from .env.example.");
                return REFUSED;
            }
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            return usage(ENV_URL + " must be a jdbc:postgresql: URL.");
        }

        String password;
        try {
            password = passwords.read(true).orElse(null);
        } catch (IOException e) {
            err.println("Could not read the password.");
            return INFRASTRUCTURE;
        }
        if (password == null) {
            return usage("No password was provided.");
        }
        List<PasswordPolicy.Problem> problems = PasswordPolicy.check(password, email.get(), workspace.get());
        if (!problems.isEmpty()) {
            problems.forEach(p -> err.println(p.message()));
            return USAGE;
        }
        String hash = PasswordHashing.create().encode(password);

        try (Connection connection = connector.open(url, dbUser, dbPassword)) {
            if (TENANT.equals(command)) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT out_tenant_id, out_user_id FROM provision_tenant(?, ?, ?, ?)")) {
                    statement.setString(1, workspace.get());
                    statement.setString(2, name);
                    statement.setString(3, email.get());
                    statement.setString(4, hash);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        out.println("Created workspace " + workspace.get() + " (tenant " + rs.getObject(1, UUID.class)
                                + ") with seller user " + rs.getObject(2, UUID.class) + ".");
                    }
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("SELECT provision_user(?, ?, ?, ?)")) {
                    statement.setString(1, workspace.get());
                    statement.setString(2, email.get());
                    statement.setString(3, role);
                    statement.setString(4, hash);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        out.println("Created " + role + " user " + rs.getObject(1, UUID.class)
                                + " in workspace " + workspace.get() + ".");
                    }
                }
            }
            return OK;
        } catch (SQLException e) {
            return databaseFailure(e);
        }
    }

    private int databaseFailure(SQLException e) {
        // Only the SQLSTATE class is used; the message can quote the failing row, hash included.
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        switch (state) {
            case "23505":
                err.println("That workspace code or email address already exists.");
                return USAGE;
            case "P0002":
                err.println("There is no enabled workspace with that code.");
                return USAGE;
            case "23514":
                err.println("The database rejected a value (check constraint).");
                return USAGE;
            case "42501":
                err.println("The provisioning login is not allowed to do this. See the README on granting "
                        + "chatledger_provisioner.");
                return INFRASTRUCTURE;
            case "55000":
                // V020: a demo deployment holds no real workspace (task 9, D1).
                err.println("Refused: this is a demo deployment. Real workspaces are never provisioned on it.");
                return REFUSED;
            default:
                err.println("The database could not be reached or failed (SQLSTATE " + (state.isEmpty() ? "none" : state)
                        + ").");
                return INFRASTRUCTURE;
        }
    }

    private int usage(String problem) {
        err.println(problem);
        err.println("Usage:");
        err.println("  provision-tenant --workspace <code> --name <workspace name> --email <seller email>");
        err.println("  provision-user   --workspace <code> --email <email> --role <SELLER|HELPER>");
        err.println("Database access comes from PROVISION_DB_URL, PROVISION_DB_USER and PROVISION_DB_PASSWORD.");
        err.println("The user's password is typed at the prompt or piped on standard input.");
        return USAGE;
    }

    private static Set<String> allowedFlags(String command) {
        return TENANT.equals(command) ? Set.of("workspace", "name", "email") : Set.of("workspace", "email", "role");
    }

    /** Any option that looks like it carries a password, hash or secret. */
    static boolean forbiddenFlag(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("pass") || lower.contains("pwd") || lower.contains("hash")
                || lower.contains("secret") || lower.contains("credential") || lower.contains("token");
    }

    /** PROVISION_* variables that would carry the user's password or hash (not the database login). */
    static boolean forbiddenEnvironmentName(String name) {
        if (name == null || ENV_PASSWORD.equals(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("PROVISION") && (upper.contains("PASS") || upper.contains("PWD")
                || upper.contains("HASH") || upper.contains("SECRET"));
    }

    static boolean isPlaceholder(String value) {
        String v = value.strip();
        return v.startsWith("<") && v.endsWith(">");
    }

    /** A terminal prompt without echo and with confirmation, or one line of stdin when piped. */
    public static PasswordSource consoleOrStdin(InputStream stdin) {
        return confirm -> {
            java.io.Console console = interactiveConsole();
            if (console != null) {
                char[] first = console.readPassword("New user's password: ");
                if (first == null) {
                    return Optional.empty();
                }
                char[] second = confirm ? console.readPassword("Repeat the password: ") : first;
                try {
                    if (second == null || !java.util.Arrays.equals(first, second)) {
                        return Optional.empty();
                    }
                    return Optional.of(new String(first));
                } finally {
                    java.util.Arrays.fill(first, '\0');
                    if (second != null) {
                        java.util.Arrays.fill(second, '\0');
                    }
                }
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line == null || line.isEmpty() ? Optional.empty() : Optional.of(line);
        };
    }

    /**
     * The console only if a person is typing at it. Since JDK 22 {@code System.console()} is non-null even
     * when standard input is piped, so a piped password would be sent to a prompt that nobody answers.
     * {@code Console.isTerminal()} (JDK 22+) tells the two apart; on JDK 21 a null console means piped.
     */
    static java.io.Console interactiveConsole() {
        java.io.Console console = System.console();
        if (console == null) {
            return null;
        }
        try {
            boolean terminal = (boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
            return terminal ? console : null;
        } catch (NoSuchMethodException e) {
            return console;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
