package dev.chatledger.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Register 167: the owner accepted, for test scope only, the findings in {@code docker-java-transport-zerodep} 3.7.1 (shaded httpclient5
 * 5.5.1, CVE-2026-71290; shaded httpcore5 5.3.6, CVE-2026-54399 and CVE-2026-54428), until 2027-01-31 or a Testcontainers release
 * that fixes them, whichever comes first. This fails on either, so the acceptance is looked at again instead of silently carried over:
 * after the date, or when the jar on the test classpath is another release (a Testcontainers upgrade). Reads the versions the jar
 * itself records, not the POM. That the jar never reaches the application or the image is held by {@link DockerfileTest} and
 * {@code deploy/check-image.sh}.
 */
class AcceptedTestRiskTest {

    static final LocalDate REVIEW_BY = LocalDate.of(2027, 1, 31);
    private static final String META = "META-INF/maven/";

    private static String version(String groupAndArtifact) throws IOException {
        try (InputStream in = AcceptedTestRiskTest.class.getClassLoader().getResourceAsStream(META + groupAndArtifact + "/pom.properties")) {
            assertThat(in).as(groupAndArtifact + " is on the test classpath").isNotNull();
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        }
    }

    @Test
    void the_accepted_docker_java_findings_are_reviewed_by_the_date_or_on_any_upgrade() throws IOException {
        assertThat(LocalDate.now(ZoneOffset.UTC)).as("register 167 must be reviewed by " + REVIEW_BY).isBeforeOrEqualTo(REVIEW_BY);
        assertThat(version("com.github.docker-java/docker-java-transport-zerodep"))
                .as("another docker-java release: review register 167 (the findings may be fixed, or new)").isEqualTo("3.7.1");
        assertThat(version("org.apache.httpcomponents.client5/httpclient5")).as("the shaded httpclient5 the acceptance names").isEqualTo("5.5.1");
        assertThat(version("org.apache.httpcomponents.core5/httpcore5")).as("the shaded httpcore5 the acceptance names").isEqualTo("5.3.6");
    }
}
