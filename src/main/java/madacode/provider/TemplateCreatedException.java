package madacode.provider;

/**
 * Signals that a fresh {@code providers.json} template was created because
 * none existed. Carries a user-facing message; bootstrap layer catches this
 * and exits cleanly (exit code 0) so the user can edit the template.
 */
public final class TemplateCreatedException extends ProviderException {
    public TemplateCreatedException(String message) {
        super(message);
    }
}
