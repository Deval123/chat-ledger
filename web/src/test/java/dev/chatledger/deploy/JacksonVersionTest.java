package dev.chatledger.deploy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.Version;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jackson 3 on the classpath (and so in the image) has the fixes for the advisories the image scan found in 3.1.5 (task 13:
 * jackson-core CVE-2026-89407, CVE-2026-89425; jackson-databind CVE-2026-68497, CVE-2026-91776, CVE-2026-91777; fixed in 3.1.7).
 * Boot 4.1.1 manages 3.1.5; the root POM overrides {@code jackson-bom.version}. This fails if the override is removed while Boot still
 * manages a vulnerable release, and if core and databind drift apart. Reads the versions the libraries report, not the POM.
 */
class JacksonVersionTest {

    private static final Version FIXED = new Version(3, 1, 7, null, "tools.jackson.core", "jackson-core");

    @Test
    void jackson_core_and_databind_are_at_least_the_fixed_release_and_agree() {
        Version core = new JsonFactory().version();
        Version databind = JsonMapper.builder().build().version();
        assertThat(core.getMajorVersion()).as("Jackson 3").isEqualTo(3);
        assertThat(compare(core, FIXED)).as("jackson-core " + core + " has the 3.1.7 fixes").isGreaterThanOrEqualTo(0);
        assertThat(compare(databind, FIXED)).as("jackson-databind " + databind + " has the 3.1.7 fixes").isGreaterThanOrEqualTo(0);
        assertThat(databind.toString()).as("core and databind from one release").isEqualTo(core.toString());
    }

    /** By number only (major, minor, patch); the group and artifact ids differ between the two libraries. */
    private static int compare(Version a, Version b) {
        int c = Integer.compare(a.getMajorVersion(), b.getMajorVersion());
        if (c == 0) {
            c = Integer.compare(a.getMinorVersion(), b.getMinorVersion());
        }
        return c != 0 ? c : Integer.compare(a.getPatchLevel(), b.getPatchLevel());
    }
}
