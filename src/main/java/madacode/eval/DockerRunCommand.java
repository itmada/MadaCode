package madacode.eval;

import java.util.ArrayList;
import java.util.List;

/** Builds docker-run invocations with one consistent shell-entrypoint contract. */
final class DockerRunCommand {

    private DockerRunCommand() {
    }

    static List<String> shell(
            String dockerCommand,
            String image,
            List<String> resourceArgs,
            List<String> extraArgs,
            List<String> envArgs,
            List<String> volumeArgs,
            String workingDirectory,
            String script) {
        List<String> command = new ArrayList<>();
        command.add(dockerCommand);
        command.add("run");
        command.add("--rm");
        command.add("--entrypoint");
        command.add("sh");
        command.addAll(safe(extraArgs));
        command.addAll(safe(resourceArgs));
        command.addAll(safe(envArgs));
        command.addAll(safe(volumeArgs));
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            command.add("-w");
            command.add(workingDirectory);
        }
        command.add(image);
        command.add("-c");
        command.add(script);
        return List.copyOf(command);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
