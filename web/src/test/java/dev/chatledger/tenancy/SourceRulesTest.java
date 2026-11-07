package dev.chatledger.tenancy;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level rules that keep the tenant on the server side, with the scanners
 * proven on invented bad input so a green result means something. No Docker.
 *
 * <ul>
 *   <li>No controller takes a tenant id from the request (path, parameter, header,
 *       cookie or body field named like a tenant).</li>
 *   <li>No SQL in {@code web} main code is built by string concatenation.</li>
 *   <li>Every repository class is registered with a cross-tenant read test.</li>
 * </ul>
 *
 * <p>Design: "Tenancy and authentication", decision 2. Task 3.3.
 */
class SourceRulesTest {

    private static final Pattern TENANT_NAME = Pattern.compile("(?i).*tenant.*");

    // ── Tenant never comes from the request ─────────────────────────────────

    @Test
    void no_real_controller_takes_a_tenant_from_the_request() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> violations = new ArrayList<>();
        int controllers = 0;
        for (var bean : provider.findCandidateComponents("dev.chatledger")) {
            controllers++;
            violations.addAll(tenantInputs(load(bean.getBeanClassName())));
        }
        assertThat(controllers).as("the scan found the controllers, so this is not vacuous").isGreaterThanOrEqualTo(1);
        assertThat(violations).isEmpty();
    }

    @Test
    void the_scanner_flags_tenant_parameters_headers_and_body_fields() {
        assertThat(tenantInputs(BadByParameter.class)).anyMatch(v -> v.contains("tenantId"));
        assertThat(tenantInputs(BadByPathVariable.class)).anyMatch(v -> v.contains("tenant"));
        assertThat(tenantInputs(BadByHeader.class)).anyMatch(v -> v.contains("X-Tenant"));
        assertThat(tenantInputs(BadByBodyRecord.class)).anyMatch(v -> v.contains("tenantId"));
        assertThat(tenantInputs(BadByBodyClass.class)).anyMatch(v -> v.contains("tenantRef"));
    }

    @Test
    void the_scanner_accepts_a_clean_controller() {
        assertThat(tenantInputs(Clean.class)).isEmpty();
    }

    // Invented controllers. They carry no class-level stereotype, so Spring never registers them.
    static class BadByParameter {
        @PostMapping("/x") void handle(@RequestParam String tenantId) { }
    }

    static class BadByPathVariable {
        @PostMapping("/x/{t}") void handle(@PathVariable("t") String tenant) { }
    }

    static class BadByHeader {
        @PostMapping("/x") void handle(@RequestHeader("X-Tenant") String value) { }
    }

    record Body(String text, String tenantId) { }

    static class BadByBodyRecord {
        @PostMapping("/x") void handle(@RequestBody Body body) { }
    }

    static class LegacyBody {
        String text;
        String tenantRef;
    }

    static class BadByBodyClass {
        @PostMapping("/x") void handle(@RequestBody LegacyBody body) { }
    }

    record CleanBody(String text) { }

    static class Clean {
        @PostMapping("/x") void handle(@RequestBody CleanBody body, @RequestParam String externalId) { }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> tenantInputs(Class<?> controller) {
        List<String> found = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                List<String> names = new ArrayList<>();
                names.add(parameter.getName());
                for (var a : parameter.getAnnotations()) {
                    if (a instanceof RequestParam r) { names.add(r.name()); names.add(r.value()); }
                    if (a instanceof PathVariable p) { names.add(p.name()); names.add(p.value()); }
                    if (a instanceof RequestHeader h) { names.add(h.name()); names.add(h.value()); }
                }
                for (String name : names) {
                    if (TENANT_NAME.matcher(name).matches()) {
                        found.add(controller.getSimpleName() + "." + method.getName() + ": request input '" + name + "'");
                    }
                }
                collectFieldNames(parameter.getType(), new HashSet<>(), 0).stream()
                        .filter(n -> TENANT_NAME.matcher(n).matches())
                        .forEach(n -> found.add(controller.getSimpleName() + "." + method.getName()
                                + ": request body field '" + n + "'"));
            }
        }
        return found;
    }

    private static Set<String> collectFieldNames(Class<?> type, Set<Class<?>> seen, int depth) {
        Set<String> names = new TreeSet<>();
        if (depth > 3 || type.isPrimitive() || type.getName().startsWith("java.") || !seen.add(type)) {
            return names;
        }
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                names.add(c.getName());
                names.addAll(collectFieldNames(c.getType(), seen, depth + 1));
            }
        } else {
            for (var f : type.getDeclaredFields()) {
                names.add(f.getName());
                names.addAll(collectFieldNames(f.getType(), seen, depth + 1));
            }
        }
        return names;
    }

    // ── No string-built SQL ─────────────────────────────────────────────────

    @Test
    void web_main_code_builds_no_sql_by_string_concatenation() throws IOException {
        Path root = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();
        int files = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                files++;
                violations.addAll(sqlViolations(file.toString(), Files.readString(file)));
            }
        }
        assertThat(files).isGreaterThan(0);
        assertThat(violations).isEmpty();
    }

    @Test
    void the_sql_scanner_flags_concatenation_variables_and_statements() {
        assertThat(sqlViolations("A.java", "c.prepareStatement(\"SELECT * FROM t WHERE id = \" + id);")).hasSize(1);
        assertThat(sqlViolations("A.java", "c.prepareStatement(sql);")).hasSize(1);
        assertThat(sqlViolations("A.java", "c.createStatement();")).hasSize(1);
        assertThat(sqlViolations("A.java", "c.prepareStatement(String.format(\"SELECT %s\", x));")).hasSize(1);
        assertThat(sqlViolations("A.java", "c.prepareStatement(\"a\" + name + \"b\", RETURN_KEYS);")).hasSize(1);
    }

    @Test
    void the_sql_scanner_accepts_literals_and_literal_concatenation() {
        assertThat(sqlViolations("A.java", "c.prepareStatement(\"SELECT 1\");")).isEmpty();
        assertThat(sqlViolations("A.java",
                "c.prepareStatement(\"SELECT a, \"\n   + \"b FROM t WHERE id = ?\");")).isEmpty();
        assertThat(sqlViolations("A.java", "c.prepareStatement(\"SELECT ')' FROM t\");")).isEmpty();
        assertThat(sqlViolations("A.java", "c.prepareStatement(\"\"\"\n  SELECT 1\n  \"\"\");")).isEmpty();
    }

    static List<String> sqlViolations(String file, String source) {
        List<String> found = new ArrayList<>();
        for (String forbidden : List.of("createStatement(", "prepareCall(", "nativeSQL(")) {
            for (int at = source.indexOf(forbidden); at >= 0; at = source.indexOf(forbidden, at + 1)) {
                found.add(file + ":" + lineOf(source, at) + " uses " + forbidden.replace("(", "") + "; use a prepared statement");
            }
        }
        String call = "prepareStatement(";
        for (int at = source.indexOf(call); at >= 0; at = source.indexOf(call, at + 1)) {
            String argument = firstArgument(source, at + call.length());
            if (!literalOnly(argument)) {
                found.add(file + ":" + lineOf(source, at) + " prepareStatement argument is not a string literal: "
                        + argument.strip().replaceAll("\\s+", " "));
            }
        }
        return found;
    }

    /** The text of the first argument: up to the first comma or closing parenthesis outside literals. */
    private static String firstArgument(String s, int start) {
        int depth = 0;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                end = end < 0 ? s.length() : end + 3;
                out.append(s, i, end);
                i = end - 1;
            } else if (ch == '"') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '"') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(s, i, Math.min(j + 1, s.length()));
                i = j;
            } else if (ch == '(') {
                depth++;
                out.append(ch);
            } else if (ch == ')') {
                if (depth == 0) {
                    return out.toString();
                }
                depth--;
                out.append(ch);
            } else if (ch == ',' && depth == 0) {
                return out.toString();
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** True when the argument is string literals, optionally joined with '+', and nothing else. */
    private static boolean literalOnly(String argument) {
        if (!argument.contains("\"")) {
            return false;
        }
        String stripped = argument
                .replaceAll("(?s)\"\"\".*?\"\"\"", "")
                .replaceAll("\"(\\\\.|[^\"\\\\])*\"", "")
                .replaceAll("\\s+", "");
        return stripped.matches("\\+*");
    }

    private static int lineOf(String s, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ── Repositories are registered ─────────────────────────────────────────

    @Test
    void every_repository_class_is_registered_with_a_cross_tenant_test() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);
        Set<String> found = new TreeSet<>();
        for (Resource resource : resolver.getResources("classpath*:dev/chatledger/**/*Repository.class")) {
            String location = resource.getURL().toString();
            if (location.contains("/test-classes/") || location.contains("-tests.jar")) {
                continue;   // test fakes such as InMemoryLedgerRepository are not production repositories
            }
            var metadata = readers.getMetadataReader(resource).getClassMetadata();
            if (!metadata.isInterface()) {
                found.add(metadata.getClassName().substring(metadata.getClassName().lastIndexOf('.') + 1));
            }
        }
        assertThat(found).as("repository classes on the classpath").isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(IsolationRegistry.REPOSITORY_CONTRACT_TESTS.keySet());

        IsolationRegistry.REPOSITORY_CONTRACT_TESTS.forEach((repository, test) -> {
            String[] parts = test.split("#");
            try {
                Class.forName(parts[0]).getDeclaredMethod(parts[1]);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(repository + " names the contract test " + test + " which does not exist", e);
            }
        });
    }
}
