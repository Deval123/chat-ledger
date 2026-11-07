package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentLimits;
import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.TemplateException;
import dev.chatledger.extraction.TemplateProblem;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a template file into a {@link PaymentTemplateSpec}, strictly. A file is refused (with a {@link TemplateException}
 * carrying only a code) when it is over 8 KiB, is not valid JSON, repeats a key, has a field this reader does not know, or
 * has a field of the wrong type or a missing one. Nothing is defaulted and nothing is ignored.
 *
 * <p>The parser's own messages quote the input, so they are dropped: only the problem code leaves this class.
 */
public final class PaymentTemplateJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Set<String> TOP = Set.of("id", "provider", "currency", "scale", "transactionIdRequired", "zone",
            "dateFormat", "template", "examples", "counterExamples");
    private static final Set<String> EXAMPLE = Set.of("text", "expect");
    private static final Set<String> EXPECT = Set.of("amount", "transactionId", "senderName", "senderLast4", "reference", "paidAt");

    private PaymentTemplateJson() {
    }

    public static PaymentTemplateSpec parse(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > PaymentLimits.MAX_FILE_BYTES) {
            throw new TemplateException(null, TemplateProblem.FILE_TOO_LARGE);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {   // includes JacksonException; its message quotes the input and is dropped
            throw new TemplateException(null, TemplateProblem.BAD_JSON);
        }
        if (root == null || !root.isObject()) {
            throw new TemplateException(null, TemplateProblem.BAD_JSON);
        }
        String idForErrors = root.get("id") != null && root.get("id").isString() ? root.get("id").stringValue() : null;
        onlyKnown(root, TOP, idForErrors);

        String id = string(root, "id", idForErrors);
        String provider = string(root, "provider", idForErrors);
        String currency = string(root, "currency", idForErrors);
        int scale = integer(root, "scale", idForErrors);
        boolean txRequired = bool(root, "transactionIdRequired", idForErrors);
        String zone = string(root, "zone", idForErrors);
        String dateFormat = string(root, "dateFormat", idForErrors);
        String template = string(root, "template", idForErrors);

        List<PaymentTemplateSpec.Example> examples = new ArrayList<>();
        for (JsonNode e : array(root, "examples", idForErrors)) {
            if (!e.isObject()) {
                throw new TemplateException(idForErrors, TemplateProblem.WRONG_TYPE);
            }
            onlyKnown(e, EXAMPLE, idForErrors);
            JsonNode expect = e.get("expect");
            if (expect == null || !expect.isObject()) {
                throw new TemplateException(idForErrors, TemplateProblem.MISSING_FIELD);
            }
            onlyKnown(expect, EXPECT, idForErrors);
            examples.add(new PaymentTemplateSpec.Example(string(e, "text", idForErrors), new PaymentTemplateSpec.Expect(
                    optionalLong(expect, "amount", idForErrors), optionalString(expect, "transactionId", idForErrors),
                    optionalString(expect, "senderName", idForErrors), optionalString(expect, "senderLast4", idForErrors),
                    optionalString(expect, "reference", idForErrors), optionalString(expect, "paidAt", idForErrors))));
        }
        List<String> counters = new ArrayList<>();
        for (JsonNode c : array(root, "counterExamples", idForErrors)) {
            if (!c.isString()) {
                throw new TemplateException(idForErrors, TemplateProblem.WRONG_TYPE);
            }
            counters.add(c.stringValue());
        }
        return new PaymentTemplateSpec(id, provider, currency, scale, txRequired, zone, dateFormat, template, examples, counters);
    }

    private static void onlyKnown(JsonNode object, Set<String> allowed, String id) {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            if (!allowed.contains(field.getKey())) {
                throw new TemplateException(id, TemplateProblem.UNKNOWN_FIELD);
            }
        }
    }

    private static JsonNode required(JsonNode object, String name, String id) {
        JsonNode n = object.get(name);
        if (n == null || n.isNull()) {
            throw new TemplateException(id, TemplateProblem.MISSING_FIELD);
        }
        return n;
    }

    private static String string(JsonNode object, String name, String id) {
        JsonNode n = required(object, name, id);
        if (!n.isString()) {
            throw new TemplateException(id, TemplateProblem.WRONG_TYPE);
        }
        return n.stringValue();
    }

    private static String optionalString(JsonNode object, String name, String id) {
        JsonNode n = object.get(name);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isString()) {
            throw new TemplateException(id, TemplateProblem.WRONG_TYPE);
        }
        return n.stringValue();
    }

    private static int integer(JsonNode object, String name, String id) {
        JsonNode n = required(object, name, id);
        if (!n.isIntegralNumber() || !n.canConvertToInt()) {
            throw new TemplateException(id, TemplateProblem.WRONG_TYPE);
        }
        return n.intValue();
    }

    private static Long optionalLong(JsonNode object, String name, String id) {
        JsonNode n = object.get(name);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isIntegralNumber() || !n.canConvertToLong()) {
            throw new TemplateException(id, TemplateProblem.WRONG_TYPE);
        }
        return n.longValue();
    }

    private static boolean bool(JsonNode object, String name, String id) {
        JsonNode n = required(object, name, id);
        if (!n.isBoolean()) {
            throw new TemplateException(id, TemplateProblem.WRONG_TYPE);
        }
        return n.booleanValue();
    }

    private static List<JsonNode> array(JsonNode object, String name, String id) {
        JsonNode n = required(object, name, id);
        if (!n.isArray()) {
            throw new TemplateException(id, TemplateProblem.WRONG_TYPE);
        }
        List<JsonNode> out = new ArrayList<>();
        n.forEach(out::add);
        return out;
    }
}
