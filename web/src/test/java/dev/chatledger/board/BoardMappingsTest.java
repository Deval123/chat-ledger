package dev.chatledger.board;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rules for every route of the board, read from the code: each handler names its permission, each is a GET or a POST, nothing that changes
 * state is a GET (the changing handlers are exactly the POSTs), and request values are typed (a UUID, a column, a number or a short string
 * that the handler parses itself). The date and cursor parsers are strict.
 *
 * <p>Requirement: 9.1. Task 8.
 */
class BoardMappingsTest {

    private static final List<Class<?>> CONTROLLERS = List.of(BoardController.class, ActionController.class, ExportController.class);

    private static List<Method> handlers() {
        List<Method> out = new ArrayList<>();
        for (Class<?> c : CONTROLLERS) {
            for (Method m : c.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    @Test
    void every_handler_names_its_permission_and_is_a_get_or_a_post() {
        List<Method> handlers = handlers();
        assertThat(handlers).hasSizeGreaterThanOrEqualTo(24);
        for (Method m : handlers) {
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
            assertThat(pre).as(m.getName() + " has @PreAuthorize").isNotNull();
            assertThat(pre.value()).as(m.getName()).matches("hasAuthority\\('[A-Z_]+'\\)( and hasAuthority\\('[A-Z_]+'\\))*");
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            assertThat(mapping.method()).as(m.getName()).hasSize(1).containsAnyOf(RequestMethod.GET, RequestMethod.POST);
        }
    }

    @Test
    void the_handlers_that_change_things_are_posts_and_the_pages_are_gets() {
        Set<String> posts = Set.of("pasteOrder", "pastePayment", "confirm", "reject", "manual", "unmatch", "setAside", "takeBack", "rematch", "approve", "checkBooks");
        for (Method m : handlers()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            boolean isPost = mapping.method()[0] == RequestMethod.POST;
            assertThat(isPost).as(m.getName() + " is a POST exactly when it changes something").isEqualTo(posts.contains(m.getName()));
        }
    }

    @Test
    void request_values_are_typed() {
        Set<Class<?>> pathTypes = Set.of(UUID.class, BoardRepository.Column.class);
        Set<Class<?>> paramTypes = Set.of(UUID.class, String.class, int.class);
        for (Method m : handlers()) {
            for (Parameter p : m.getParameters()) {
                if (p.isAnnotationPresent(PathVariable.class)) {
                    assertThat(pathTypes).as(m.getName() + " path variable " + p.getName()).contains(p.getType());
                }
                if (p.isAnnotationPresent(RequestParam.class)) {
                    assertThat(paramTypes).as(m.getName() + " parameter " + p.getName()).contains(p.getType());
                }
            }
        }
    }

    @Test
    void a_column_is_named_in_lower_case_only() {
        for (BoardRepository.Column c : BoardRepository.Column.values()) {
            assertThat(BoardRepository.Column.of(c.path())).contains(c);
            assertThat(BoardRepository.Column.of(c.name())).isEmpty();
        }
        assertThat(BoardRepository.Column.of("nonsense")).isEmpty();
        assertThat(BoardRepository.Column.of(null)).isEmpty();
    }

    @Test
    void a_cursor_is_read_strictly_and_round_trips() {
        UUID id = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        BoardRepository.Cursor cursor = new BoardRepository.Cursor(java.time.Instant.parse("2026-11-03T10:15:30.123456Z"), id);
        assertThat(cursor.format()).isEqualTo("1793700930123456_" + id);
        assertThat(BoardRepository.Cursor.parse(cursor.format())).contains(cursor);
        for (String bad : new String[]{null, "", "x", "_" + id, "12a_" + id, "1793700930123456_" + id.toString().toUpperCase(), "1793700930123456-" + id,
                "1793700930123456_" + id + "x", "1793700930123456_" + id.toString().replace("-", ""), " 1793700930123456_" + id,
                "1793700930123456_123e4567-e89b-42d3-a456-42661417400g", "1793700930123456_123e4567ae89b-42d3-a456-426614174000", "9".repeat(19) + "_" + id, "1793700930123456_" + id + "\n"}) {
            assertThat(BoardRepository.Cursor.parse(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void a_day_is_an_iso_date_between_2000_and_today() {
        LocalDate today = LocalDate.of(2026, 11, 3);
        assertThat(BoardController.parseDay("2026-11-03", today)).isEqualTo(today);
        assertThat(BoardController.parseDay("2000-01-01", today)).isEqualTo(LocalDate.of(2000, 1, 1));
        for (String bad : new String[]{"2026-11-04", "1999-12-31", "2026-13-01", "2026-02-30", "2026-1-1", "20261103", "2026-11-03 ", "", "garbage!!!", "\u0662026-11-03"}) {
            assertThatThrownBy(() -> BoardController.parseDay(bad, today)).as(bad).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void the_row_cap_of_a_file_can_only_be_lowered() {
        for (int bad : new int[]{0, -1, 100_001, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new ExportController(null, null, null, null, null, null, bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        new ExportController(null, null, null, null, null, null, 1);
        new ExportController(null, null, null, null, null, null, 100_000);
    }
}
