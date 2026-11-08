package dev.chatledger.board;

import dev.chatledger.board.BoardRepository.Column;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets a column be named in a path in lower case ({@code /app/columns/awaiting}). Any other word is a conversion failure, hence a 400. */
@Configuration
public class BoardWebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, Column.class, (Converter<String, Column>) source ->
                Column.of(source).orElseThrow(() -> new IllegalArgumentException("not a column")));
    }
}
