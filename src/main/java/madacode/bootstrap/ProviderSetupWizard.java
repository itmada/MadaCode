package madacode.bootstrap;

import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderLoader;
import madacode.tui.Screen;
import madacode.tui.TerminalKeys;
import madacode.tui.inline.LineEditor;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ProviderSetupWizard {

    private static final String TITLE = "Configure Provider";
    private static final String FOOTER =
            "Enter=save   Tab/Shift-Tab or Up/Down=switch   Esc/Ctrl-C/Ctrl-D=cancel";

    private final ProviderLoader loader;
    private final Terminal terminal;
    private final Screen screen;

    public ProviderSetupWizard(ProviderLoader loader, TerminalRuntime terminalRuntime) {
        this.loader = Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(terminalRuntime, "terminalRuntime");
        this.terminal = Objects.requireNonNull(terminalRuntime.terminal(), "terminal");
        this.screen = Objects.requireNonNull(terminalRuntime.screen(), "screen");
    }

    public List<Provider> run() {
        Draft draft = new Draft();
        Field field = Field.PROVIDER_NAME;
        Attributes previous = null;
        try {
            try {
                previous = terminal.enterRawMode();
                screen.setCursorVisible(false);
                while (true) {
                    screen.setLiveModal(card(draft, field, loader.file(), screen.width()));

                    TerminalKeys.KeyPress key = TerminalKeys.readKey(terminal.reader());
                    switch (key.key()) {
                        case ENTER -> {
                            if (field == Field.OTHER_MODELS) {
                                ValidationIssue issue = validateAll(draft);
                                if (issue != null) {
                                    draft.setError(issue.message());
                                    field = issue.field();
                                    continue;
                                }
                                try {
                                    Provider provider = draft.toProvider();
                                    loader.save(List.of(provider));
                                    screen.clearLiveModal();
                                    screen.scrollback(List.of("Configuration saved to " + loader.file()));
                                    return List.of(provider);
                                } catch (IllegalArgumentException e) {
                                    draft.setError(e.getMessage());
                                    continue;
                                } catch (RuntimeException e) {
                                    draft.setError("Failed to save provider configuration: " + e.getMessage());
                                    continue;
                                }
                            } else {
                                ValidationIssue issue = validateField(draft, field);
                                if (issue != null) {
                                    draft.setError(issue.message());
                                    field = issue.field();
                                    continue;
                                }
                                field = field.next();
                                draft.clearError();
                            }
                        }
                        case ESCAPE, CTRL_C, CTRL_D, EOF -> throw cancelled();
                        case TAB, DOWN -> {
                            field = field.next();
                            draft.clearError();
                        }
                        case SHIFT_TAB, UP -> {
                            field = field.previous();
                            draft.clearError();
                        }
                        case LEFT -> draft.moveCursorLeft(field);
                        case RIGHT -> draft.moveCursorRight(field);
                        case HOME -> draft.home(field);
                        case END -> draft.end(field);
                        case BACKSPACE -> {
                            if (draft.backspace(field)) {
                                draft.clearError();
                            }
                        }
                        case DELETE -> {
                            if (draft.delete(field)) {
                                draft.clearError();
                            }
                        }
                        case PASTE -> {
                            if (draft.insert(field, sanitizePaste(key.text()))) {
                                draft.clearError();
                            }
                        }
                        default -> {
                            if (key.isPrintable() && draft.insert(field, Character.toString((char) key.ch()))) {
                                draft.clearError();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new BootstrapException("Provider setup failed: " + e.getMessage(), 1);
            } finally {
                try {
                    if (previous != null) {
                        terminal.setAttributes(previous);
                    }
                } finally {
                    try {
                        screen.clearLiveModal();
                    } finally {
                        screen.setCursorVisible(true);
                    }
                }
            }
        } catch (BootstrapException e) {
            throw e;
        }
    }

    static List<String> card(Draft draft, String activeFieldLabel, Path configPath, int width) {
        Objects.requireNonNull(configPath, "configPath");
        return card(draft, Field.fromLabel(activeFieldLabel), configPath, width);
    }

    static List<String> card(Draft draft, Field activeField, Path configPath, int width) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(configPath, "configPath");

        // Build intro lines
        List<String> introLines = List.of(
                "MadaCode needs a model provider before it can run.",
                "Create " + configPath);

        // Build field rows
        List<madacode.tui.widget.ProviderSetupPanel.FieldRow> fieldRows = new ArrayList<>();
        for (Field field : Field.values()) {
            String displayValue = field == Field.AUTH_TOKEN
                    ? draft.renderMaskedToken(activeField == field)
                    : draft.renderField(field, activeField == field);
            fieldRows.add(new madacode.tui.widget.ProviderSetupPanel.FieldRow(
                    field.label(),
                    displayValue,
                    activeField == field));
        }

        // Build view and render
        madacode.tui.widget.ProviderSetupPanel.SetupView view =
                new madacode.tui.widget.ProviderSetupPanel.SetupView(
                        TITLE,
                        introLines,
                        fieldRows,
                        draft.error(),
                        FOOTER);

        List<AttributedString> rendered = madacode.tui.widget.ProviderSetupPanel.render(view, Math.max(20, width));

        // Convert AttributedString to String
        List<String> lines = new ArrayList<>();
        for (AttributedString as : rendered) {
            lines.add(as.toAnsi());
        }
        return lines;
    }

    private static ValidationIssue validateAll(Draft draft) {
        for (Field field : Field.values()) {
            if (!field.required()) {
                continue;
            }
            ValidationIssue issue = validateField(draft, field);
            if (issue != null) {
                return issue;
            }
        }
        return null;
    }

    private static ValidationIssue validateField(Draft draft, Field field) {
        String value = draft.text(field).trim();
        return switch (field) {
            case PROVIDER_NAME -> blankIssue(value, field, "Provider name is required.");
            case BASE_URL -> validateBaseUrl(value);
            case AUTH_TOKEN -> blankIssue(value, field, "Auth token is required.");
            case DEFAULT_MODEL -> blankIssue(value, field, "Default model is required.");
            case OTHER_MODELS -> null;
        };
    }

    private static ValidationIssue validateBaseUrl(String value) {
        if (value.isBlank()) {
            return new ValidationIssue(Field.BASE_URL, "Base URL must be a valid http or https URL.");
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                return new ValidationIssue(Field.BASE_URL, "Base URL must be a valid http or https URL.");
            }
            return null;
        } catch (IllegalArgumentException e) {
            return new ValidationIssue(Field.BASE_URL, "Base URL must be a valid http or https URL.");
        }
    }

    private static ValidationIssue blankIssue(String value, Field field, String message) {
        if (value.isBlank()) {
            return new ValidationIssue(field, message);
        }
        return null;
    }

    private static BootstrapException cancelled() {
        return new BootstrapException("Provider setup cancelled.", 0);
    }

    private static String sanitizePaste(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r' || cp == '\n' || cp == '\t') {
                cleaned.append(' ');
            } else if (!Character.isISOControl(cp)) {
                cleaned.appendCodePoint(cp);
            }
        }
        return cleaned.toString();
    }

    private record ValidationIssue(Field field, String message) {
    }

    public enum Field {
        PROVIDER_NAME("Provider name", true),
        BASE_URL("Base URL", true),
        AUTH_TOKEN("Auth token", true),
        DEFAULT_MODEL("Default model", true),
        OTHER_MODELS("Other models", false);

        private static final Field[] ORDER = values();

        private final String label;
        private final boolean required;

        Field(String label, boolean required) {
            this.label = label;
            this.required = required;
        }

        public String label() {
            return label;
        }

        public boolean required() {
            return required;
        }

        public Field next() {
            return ORDER[(ordinal() + 1) % ORDER.length];
        }

        public Field previous() {
            return ORDER[(ordinal() - 1 + ORDER.length) % ORDER.length];
        }

        static Field fromLabel(String label) {
            if (label == null || label.isBlank()) {
                return null;
            }
            String trimmed = label.trim();
            for (Field field : ORDER) {
                if (field.label.equals(trimmed)) {
                    return field;
                }
            }
            return null;
        }
    }

    public static final class Draft {

        private static final String EMPTY_VALUE = "-";

        private final EnumMap<Field, LineEditor> fields = new EnumMap<>(Field.class);
        private String error;

        public Draft() {
            for (Field field : Field.values()) {
                fields.put(field, new LineEditor());
            }
        }

        public Draft set(Field field, String value) {
            return set(field, value, value == null ? 0 : value.length());
        }

        public Draft set(Field field, String value, int cursor) {
            state(field).set(value, cursor);
            return this;
        }

        public String text(Field field) {
            return state(field).text();
        }

        public int cursor(Field field) {
            return state(field).cursor();
        }

        public Draft setCursor(Field field, int cursor) {
            state(field).cursor(cursor);
            return this;
        }

        public boolean insert(Field field, String text) {
            return state(field).insert(text);
        }

        public boolean backspace(Field field) {
            return state(field).backspace();
        }

        public boolean delete(Field field) {
            return state(field).delete();
        }

        public void moveCursorLeft(Field field) {
            state(field).moveLeft();
        }

        public void moveCursorRight(Field field) {
            state(field).moveRight();
        }

        public void home(Field field) {
            state(field).home();
        }

        public void end(Field field) {
            state(field).end();
        }

        public String error() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public void clearError() {
            this.error = null;
        }

        public Provider toProvider() {
            String providerName = trimmed(text(Field.PROVIDER_NAME));
            String baseUrlText = trimmed(text(Field.BASE_URL));
            String authToken = trimmed(text(Field.AUTH_TOKEN));
            String defaultModel = trimmed(text(Field.DEFAULT_MODEL));
            String otherModels = text(Field.OTHER_MODELS);

            if (providerName.isBlank()) {
                throw new IllegalArgumentException("Provider name is required.");
            }
            if (authToken.isBlank()) {
                throw new IllegalArgumentException("Auth token is required.");
            }
            if (defaultModel.isBlank()) {
                throw new IllegalArgumentException("Default model is required.");
            }

            URI baseUrl = parseBaseUrl(baseUrlText);
            List<Model> models = models(defaultModel, otherModels);
            return new Provider(providerName, authToken, baseUrl, defaultModel, models);
        }

        private String renderField(Field field, boolean active) {
            String value = text(field);
            if (value.isEmpty()) {
                return active ? "█" : EMPTY_VALUE;
            }
            if (!active) {
                return value;
            }
            int cursor = Math.max(0, Math.min(cursor(field), value.length()));
            return value.substring(0, cursor) + "█" + value.substring(cursor);
        }

        private String renderMaskedToken(boolean active) {
            int tokenLength = text(Field.AUTH_TOKEN).length();
            if (tokenLength == 0) {
                return active ? "█" : EMPTY_VALUE;
            }
            String mask = "*".repeat(tokenLength);
            if (!active) {
                return mask;
            }
            int cursor = Math.max(0, Math.min(cursor(Field.AUTH_TOKEN), mask.length()));
            return mask.substring(0, cursor) + "█" + mask.substring(cursor);
        }

        private static URI parseBaseUrl(String value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Base URL must be a valid http or https URL.");
            }
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("Base URL must be a valid http or https URL.");
            }
            return uri;
        }

        private static List<Model> models(String defaultModel, String otherModels) {
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            ordered.add(defaultModel.trim());
            if (otherModels != null && !otherModels.isBlank()) {
                for (String candidate : otherModels.trim().split("\\s+")) {
                    String model = candidate.trim();
                    if (!model.isBlank()) {
                        ordered.add(model);
                    }
                }
            }

            List<Model> models = new ArrayList<>(ordered.size());
            for (String model : ordered) {
                models.add(new Model(model, Model.DEFAULT_CONTEXT_WINDOW));
            }
            return models;
        }

        private LineEditor state(Field field) {
            return Objects.requireNonNull(fields.get(field), "field");
        }

        private static String trimmed(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
