package dev.chatledger.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Secrets for the operator commands (task 13, D6): a variable {@code NAME} may instead be given as {@code NAME_FILE}, the path of a file
 * that holds the value (a Compose secret under {@code /run/secrets/}). The value then never appears in the process environment, in
 * {@code docker inspect} or in a crash report.
 *
 * <p>Refused, with a sentence that names the variable and never the value: both {@code NAME} and {@code NAME_FILE} set; a file that is
 * missing, not a regular file, readable by everyone (where the file system says so), empty, larger than 4 KiB, or not a single line.
 * One trailing newline is removed (the generation command writes one).
 */
public final class SecretFiles {

    /** A refused secret; the message names the variable only. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }

    static final int MAX_BYTES = 4096;

    private SecretFiles() {
    }

    /** A copy of {@code env} in which each of {@code names} holds its value, read from {@code NAME_FILE} where that is given. */
    public static Map<String, String> resolve(Map<String, String> env, List<String> names) {
        Map<String, String> out = new HashMap<>(env);
        for (String name : names) {
            String file = env.get(name + "_FILE");
            if (file == null) {
                continue;
            }
            if (env.get(name) != null) {
                throw new Refused("Refused: both " + name + " and " + name + "_FILE are set. Give only one.");
            }
            out.remove(name + "_FILE");
            out.put(name, read(name, Path.of(file)));
        }
        return out;
    }

    static String read(String name, Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file)) {
                throw new Refused("Refused: " + name + "_FILE does not name a readable file.");
            }
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
                if (permissions.contains(PosixFilePermission.OTHERS_READ) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new Refused("Refused: the file of " + name + "_FILE can be read or written by everyone. Make it 0440 or stricter.");
                }
            }
            if (Files.size(file) > MAX_BYTES) {
                throw new Refused("Refused: the file of " + name + "_FILE is larger than " + MAX_BYTES + " bytes.");
            }
            String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new Refused("Refused: the file of " + name + "_FILE must hold one line.");
            }
            return value;
        } catch (IOException e) {
            throw new Refused("Refused: the file of " + name + "_FILE could not be read.");
        }
    }
}
