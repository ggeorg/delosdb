package io.github.ggeorg.delosdb.engine.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Human-readable formatter for DelosDB modern RDBMS trace events.
 *
 * <p>The formatter is diagnostic-only. It does not subscribe to the trace registry, does not
 * decide whether tracing is enabled, and does not participate in query planning or execution. It
 * simply turns already-captured trace events into stable text that a focused proof, tutorial, or
 * research note can show to a reader.</p>
 */
public final class RdbmsTraceFormatter {
    private RdbmsTraceFormatter() {
    }

    public static String format(RdbmsTraceEvent event) {
        Objects.requireNonNull(event, "event");

        StringBuilder builder = new StringBuilder();
        builder.append(event.stage().name())
                .append(' ')
                .append(event.subject());

        if (!event.attributes().isEmpty()) {
            builder.append(" [");
            appendAttributes(builder, event.attributes());
            builder.append(']');
        }

        return builder.toString();
    }

    public static String format(Iterable<RdbmsTraceEvent> events) {
        Objects.requireNonNull(events, "events");

        StringBuilder builder = new StringBuilder();
        for (RdbmsTraceEvent event : events) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(format(event));
        }
        return builder.toString();
    }

    private static void appendAttributes(
            StringBuilder builder,
            Map<String, String> attributes) {
        List<String> names = new ArrayList<>(attributes.keySet());
        Collections.sort(names);

        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            String name = names.get(i);
            builder.append(name)
                    .append("=\"")
                    .append(escape(attributes.get(name)))
                    .append('"');
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
            case '\\':
                builder.append("\\\\");
                break;
            case '"':
                builder.append("\\\"");
                break;
            case '\n':
                builder.append("\\n");
                break;
            case '\r':
                builder.append("\\r");
                break;
            case '\t':
                builder.append("\\t");
                break;
            default:
                builder.append(ch);
                break;
            }
        }
        return builder.toString();
    }
}
