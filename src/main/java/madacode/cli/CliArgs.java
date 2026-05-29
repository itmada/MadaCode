package madacode.cli;

import java.util.ArrayList;
import java.util.List;

public sealed interface CliArgs {

    boolean noMemory();

    boolean dangerouslyBypassPermissions();

    String providerOverride();

    record Interactive(boolean noMemory, boolean dangerouslyBypassPermissions, String providerOverride) implements CliArgs {
        public Interactive { if (providerOverride != null && providerOverride.isBlank()) providerOverride = null; }
    }
    record NewSession(boolean noMemory, boolean dangerouslyBypassPermissions, String providerOverride) implements CliArgs {
        public NewSession { if (providerOverride != null && providerOverride.isBlank()) providerOverride = null; }
    }
    record Resume(String sessionId, boolean noMemory, boolean dangerouslyBypassPermissions, String providerOverride) implements CliArgs {
        public Resume {
            if (sessionId == null || sessionId.isBlank())
                throw new IllegalArgumentException("sessionId must not be blank");
            if (providerOverride != null && providerOverride.isBlank()) providerOverride = null;
        }
    }
    record Continue(boolean noMemory, boolean dangerouslyBypassPermissions, String providerOverride) implements CliArgs {
        public Continue { if (providerOverride != null && providerOverride.isBlank()) providerOverride = null; }
    }
    record ListSessions(boolean noMemory) implements CliArgs {
        @Override
        public boolean dangerouslyBypassPermissions() {
            return false;
        }
        @Override
        public String providerOverride() { return null; }
    }
    record Help(boolean noMemory) implements CliArgs {
        @Override
        public boolean dangerouslyBypassPermissions() {
            return false;
        }
        @Override
        public String providerOverride() { return null; }
    }

    // ---- parser --------------------------------------------------------

    static CliArgs parse(String[] args) {
        if (args == null || args.length == 0) {
            return new Interactive(false, false, null);
        }

        // Extract global flags before mode routing
        List<String> filtered = new ArrayList<>();
        boolean noMemory = false;
        boolean dangerouslyBypassPermissions = false;
        String providerOverride = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--no-memory".equals(arg)) {
                noMemory = true;
            } else if ("--dangerously-bypass-permissions".equals(arg)) {
                dangerouslyBypassPermissions = true;
            } else if ("--provider".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("-")) {
                    throw new IllegalArgumentException("--provider requires a provider name");
                }
                providerOverride = args[++i];
            } else if (arg.startsWith("--provider=")) {
                String value = arg.substring("--provider=".length());
                if (value.isBlank()) {
                    throw new IllegalArgumentException("--provider= requires a non-empty provider name");
                }
                providerOverride = value;
            } else {
                filtered.add(arg);
            }
        }

        String[] remaining = filtered.toArray(String[]::new);
        if (remaining.length == 0) {
            return new Interactive(noMemory, dangerouslyBypassPermissions, providerOverride);
        }

        // Normalize "--flag=value" and "--flag value" into a uniform pair
        String first = remaining[0];
        String flag, value;
        if (first.startsWith("--") && first.contains("=")) {
            int eq = first.indexOf('=');
            flag = first.substring(0, eq);
            value = first.substring(eq + 1);
        } else {
            flag = first;
            value = remaining.length >= 2 ? remaining[1] : "";
        }

        return switch (flag) {
            case "--help", "-h" -> new Help(noMemory);
            case "--new"       -> new NewSession(noMemory, dangerouslyBypassPermissions, providerOverride);
            case "--continue", "-c" -> new Continue(noMemory, dangerouslyBypassPermissions, providerOverride);
            case "--list", "-l" -> new ListSessions(noMemory);
            case "--resume", "-r" -> {
                if (value.isBlank())
                    throw new IllegalArgumentException("--resume requires a session ID");
                yield new Resume(value, noMemory, dangerouslyBypassPermissions, providerOverride);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown argument(s): " + String.join(" ", remaining)
                            + "\nUsage: mada [--new|--continue|-c|--resume <id>|-r <id>|--list|-l|--help|-h] [--no-memory] [--dangerously-bypass-permissions] [--provider <name>]");
        };
    }
}
