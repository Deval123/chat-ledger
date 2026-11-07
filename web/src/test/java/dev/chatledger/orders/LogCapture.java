package dev.chatledger.orders;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Captures everything logged at DEBUG and above while open: messages and exception texts. */
final class LogCapture implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Level before = root.getLevel();

    LogCapture() {
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
    }

    String text() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : List.copyOf(appender.list)) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                sb.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(before);
    }
}
