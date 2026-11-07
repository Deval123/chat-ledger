package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.TemplateException;
import dev.chatledger.extraction.TemplateProblem;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads {@code payment-templates/*.json} from the jar, in file-name order. */
@Component
public class ClasspathPaymentTemplateSource implements PaymentTemplateSource {

    private final String location;

    public ClasspathPaymentTemplateSource() {
        this("classpath*:payment-templates/*.json");
    }

    ClasspathPaymentTemplateSource(String location) {
        this.location = location;
    }

    @Override
    public List<PaymentTemplateSpec> load() {
        List<PaymentTemplateSpec> specs = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
            java.util.Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    byte[] bytes = in.readNBytes(dev.chatledger.extraction.PaymentLimits.MAX_FILE_BYTES + 1);
                    specs.add(PaymentTemplateJson.parse(new String(bytes, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            throw new TemplateException(null, TemplateProblem.BAD_JSON);
        }
        return specs;
    }
}
