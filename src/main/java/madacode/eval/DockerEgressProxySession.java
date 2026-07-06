package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import madacode.governance.EgressEvent;
import madacode.governance.EgressObservation;
import madacode.governance.EgressReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Host-side lifecycle for the Phase-2 docker egress network and proxy sidecar. */
final class DockerEgressProxySession implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PROXY_PORT = 8080;
    private static final Duration DOCKER_TIMEOUT = Duration.ofSeconds(15);

    private final String dockerCommand;
    private final String image;
    private final List<String> resourceArgs;
    private final Path projectDir;
    private final ProcessSupervisor supervisor;
    private final String networkName;
    private final String containerName;
    private final Path configDir;
    private final Path eventLog;
    private final List<String> secrets;
    private ProcessSupervisor.ManagedProcess proxyProcess;
    private boolean networkCreated;

    DockerEgressProxySession(
            String dockerCommand,
            String image,
            List<String> resourceArgs,
            Path projectDir,
            List<DockerEgressProxyConfigJson.ProviderRouteJson> providerRoutes,
            Map<String, String> providerEnv,
            List<String> secrets) {
        this.dockerCommand = dockerCommand;
        this.image = image;
        this.resourceArgs = resourceArgs == null ? List.of() : List.copyOf(resourceArgs);
        this.projectDir = projectDir;
        this.supervisor = new ProcessSupervisor();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toLowerCase(Locale.ROOT);
        this.networkName = "mada-eval-egress-" + suffix;
        this.containerName = "mada-eval-proxy-" + suffix;
        this.secrets = secrets == null ? List.of() : List.copyOf(secrets);
        try {
            this.configDir = Files.createTempDirectory("mada-eval-egress-proxy-");
            this.eventLog = configDir.resolve("egress-events.jsonl");
            Files.createFile(eventLog);
            Path configFile = configDir.resolve("proxy-config.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                    configFile.toFile(),
                    new DockerEgressProxyConfigJson(
                            DockerEgressProxyConfigJson.SCHEMA_VERSION,
                            PROXY_PORT,
                            providerRoutes));
            start(configFile, providerEnv);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to prepare docker egress proxy", e);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    String networkName() {
        return networkName;
    }

    int proxyPort() {
        return PROXY_PORT;
    }

    String containerName() {
        return containerName;
    }

    EgressReport egressReport() {
        List<EgressEvent> events = readEvents();
        return new EgressReport(EgressObservation.OBSERVED, events);
    }

    private void start(Path configFile, Map<String, String> providerEnv) {
        ProcessSupervisor.Outcome network = supervisor.run(
                List.of(dockerCommand, "network", "create", "--internal", networkName),
                Path.of("").toAbsolutePath(),
                DOCKER_TIMEOUT,
                64 * 1024);
        if (!network.succeeded()) {
            throw new IllegalStateException("failed to create internal docker network: " + network.output());
        }
        networkCreated = true;

        List<String> envArgs = new ArrayList<>();
        if (providerEnv != null) {
            providerEnv.keySet().forEach(name -> {
                envArgs.add("-e");
                envArgs.add(name);
            });
        }
        List<String> extraArgs = new ArrayList<>();
        extraArgs.add("--name");
        extraArgs.add(containerName);
        extraArgs.add("--network");
        extraArgs.add(networkName);
        extraArgs.add("--network-alias");
        extraArgs.add("mada-egress-proxy");
        List<String> volumes = List.of(
                "-v", configFile.toAbsolutePath() + ":/proxy/config.json:ro",
                "-v", eventLog.toAbsolutePath() + ":/proxy/egress-events.jsonl:rw",
                "-v", projectDir.resolve("target/MadaCode.jar").toAbsolutePath() + ":/app/MadaCode.jar:ro");
        List<String> command = DockerRunCommand.shell(
                dockerCommand,
                image,
                resourceArgs,
                extraArgs,
                envArgs,
                volumes,
                "/proxy",
                "java -cp /app/MadaCode.jar madacode.eval.DockerEgressProxyMain "
                        + "--config /proxy/config.json --event-log /proxy/egress-events.jsonl");
        proxyProcess = supervisor.start(command, Path.of("").toAbsolutePath(), 128 * 1024, providerEnv);
        waitUntilProxyIsRunning();
        connectProxyToEgressNetwork();
    }

    private void connectProxyToEgressNetwork() {
        ProcessSupervisor.Outcome connect = supervisor.run(
                List.of(dockerCommand, "network", "connect", "bridge", containerName),
                Path.of("").toAbsolutePath(),
                DOCKER_TIMEOUT,
                64 * 1024);
        if (!connect.succeeded()) {
            throw new IllegalStateException("failed to attach egress proxy to docker bridge network: "
                    + connect.output());
        }
    }

    private void waitUntilProxyIsRunning() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            ProcessSupervisor.Outcome inspect = supervisor.run(
                    List.of(dockerCommand, "inspect", "-f", "{{.State.Running}}", containerName),
                    Path.of("").toAbsolutePath(),
                    Duration.ofSeconds(2),
                    4096);
            if (inspect.succeeded() && "true".equals(inspect.output().strip())) {
                ProcessSupervisor.Outcome health = supervisor.run(
                        DockerRunCommand.shell(
                                dockerCommand,
                                image,
                                List.of(),
                                List.of("--network", networkName),
                                List.of(),
                                List.of(),
                                "/tmp",
                                "curl -fsS http://mada-egress-proxy:" + PROXY_PORT + "/health"),
                        Path.of("").toAbsolutePath(),
                        Duration.ofSeconds(3),
                        4096);
                if (health.succeeded()) {
                    return;
                }
            }
            if (proxyProcess != null && !proxyProcess.isAlive()) {
                ProcessSupervisor.Outcome outcome = proxyProcess.await(Duration.ofMillis(1));
                throw new IllegalStateException("egress proxy container exited early: " + outcome.output());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for egress proxy");
            }
        }
        throw new IllegalStateException("egress proxy did not become ready: " + proxyProcess.output());
    }

    private List<EgressEvent> readEvents() {
        if (!Files.isRegularFile(eventLog)) {
            return List.of();
        }
        try {
            List<EgressEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(eventLog)) {
                if (line.isBlank()) {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(line);
                events.add(new EgressEvent(
                        redact(node.path("destination").asText("")),
                        node.path("blocked").asBoolean(false),
                        redact(node.path("detail").asText(""))));
            }
            return List.copyOf(events);
        } catch (IOException e) {
            return List.of(new EgressEvent("", true,
                    "kind=blocked;reason=egress-log-unreadable;error=" + e.getMessage()));
        }
    }

    private String redact(String value) {
        String redacted = value == null ? "" : value;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, "[REDACTED_PROVIDER_TOKEN]");
            }
        }
        return redacted;
    }

    @Override
    public void close() {
        if (proxyProcess != null) {
            proxyProcess.close();
            proxyProcess = null;
        }
        supervisor.run(
                List.of(dockerCommand, "rm", "-f", containerName),
                Path.of("").toAbsolutePath(),
                DOCKER_TIMEOUT,
                64 * 1024);
        if (networkCreated) {
            supervisor.run(
                    List.of(dockerCommand, "network", "rm", networkName),
                    Path.of("").toAbsolutePath(),
                    DOCKER_TIMEOUT,
                    64 * 1024);
        }
        DockerEvalExecutionEnvironment.deleteTree(configDir);
    }
}
