package dev.chatledger.extraction;

/**
 * A template was refused. The message holds the template id (only if it is a valid id) and the problem code, never the
 * template text, an example, or anything from a message.
 */
public final class TemplateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String templateId;
    private final TemplateProblem problem;

    public TemplateException(String templateId, TemplateProblem problem) {
        super("template " + safe(templateId) + ": " + problem, null, false, false);
        this.templateId = safe(templateId);
        this.problem = problem;
    }

    public String templateId() {
        return templateId;
    }

    public TemplateProblem problem() {
        return problem;
    }

    private static String safe(String id) {
        return id != null && PaymentTemplate.validId(id) ? id : "(invalid id)";
    }
}
