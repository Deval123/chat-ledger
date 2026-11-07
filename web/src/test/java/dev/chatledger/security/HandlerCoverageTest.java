package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The URL rules are the coarse gate; the permission on each handler is the fine one, and it is what
 * survives if a path is moved. Every request handler must either carry {@code @PreAuthorize} or be on
 * the short list of public pages below. A new handler without either fails the build.
 *
 * <p>Requirement: 9.1. Task 3.7.
 */
class HandlerCoverageTest {

    /** Public on purpose: the permitAll paths of SecurityConfig. Nothing else. */
    private static final Set<String> PUBLIC_HANDLERS = Set.of(
            "HealthController.health",
            "HomeController.root",
            "LoginController.loginPage",
            "LoginController.login");

    @Test
    void every_handler_has_a_permission_or_is_on_the_public_list() throws Exception {
        List<String> unprotected = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                String name = controller.getSimpleName() + "." + method.getName();
                seen.add(name);
                if (!AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)
                        && !AnnotatedElementUtils.hasAnnotation(controller, PreAuthorize.class)
                        && !PUBLIC_HANDLERS.contains(name)) {
                    unprotected.add(name);
                }
            }
        }
        assertThat(seen).as("the scan found the handlers").contains("HomeController.home", "LoginController.login");
        assertThat(unprotected).isEmpty();
        assertThat(seen).as("the public list names handlers that exist").containsAll(PUBLIC_HANDLERS);
    }

    @Test
    void the_check_catches_an_unprotected_handler() {
        assertThat(unprotectedIn(Bare.class)).containsExactly("Bare.open");
        assertThat(unprotectedIn(Guarded.class)).isEmpty();
    }

    static class Bare {
        @org.springframework.web.bind.annotation.GetMapping("/x") String open() { return "x"; }
    }

    static class Guarded {
        @org.springframework.web.bind.annotation.GetMapping("/x") @PreAuthorize("hasAuthority('VIEW_BOARD')") String shut() { return "x"; }
    }

    private static List<String> unprotectedIn(Class<?> controller) {
        List<String> out = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)
                    && !AnnotatedElementUtils.hasAnnotation(m, PreAuthorize.class)) {
                out.add(controller.getSimpleName() + "." + m.getName());
            }
        }
        return out;
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> classes = new ArrayList<>();
        for (var bean : provider.findCandidateComponents("dev.chatledger")) {
            classes.add(Class.forName(bean.getBeanClassName()));
        }
        return classes;
    }
}
