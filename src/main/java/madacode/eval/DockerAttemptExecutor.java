package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.bootstrap.HeadlessAgentRuntime;
import madacode.services.api.ApiErrorType;
import madacode.services.api.ApiFailureClassification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Phase-1 Docker attempt executor.
 *
 * <p>This executor currently supports the no-model self-test path. It proves the container
 * file protocol and docker-backed verify isolation without pretending that real provider
 * runtime packaging has been completed.
 */
final class DockerAttemptExecutor implements AttemptExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DOCKER_INFO_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOCKER_RUN_GRACE = Duration.ofSeconds(15);

    private final HeadlessAgentRuntime runtime;
    private final Path projectDir;
    private final boolean runtimeMode;
    private final String scorerFingerprint;
    private final String dockerCommand;
    private final String image;
    private final List<String> resourceArgs;
    private final String resourceLimits;

    DockerAttemptExecutor(HeadlessAgentRuntime runtime, String scorerFingerprint) {
        this(
                runtime,
                scorerFingerprint,
                System.getenv().getOrDefault("MADA_EVAL_DOCKER_CMD", "docker"),
                System.getenv().getOrDefault("MADA_EVAL_DOCKER_IMAGE", "eclipse-temurin:21-jdk"),
                List.of("--memory", "512m", "--cpus", "1", "--pids-limit", "256"),
                "memory=512m;cpus=1;pids=256");
    }

    DockerAttemptExecutor(
            HeadlessAgentRuntime runtime,
            String scorerFingerprint,
            String dockerCommand,
            String image,
            List<String> resourceArgs,
            String resourceLimits) {
        this.runtime = runtime;
        this.projectDir = runtime == null ? Path.of("").toAbsolutePath().normalize() : runtime.projectDir();
        this.runtimeMode = runtime != null;
        this.scorerFingerprint = scorerFingerprint == null ? "(none)" : scorerFingerprint;
        this.dockerCommand = Objects.requireNonNull(dockerCommand, "dockerCommand");
        this.image = Objects.requireNonNull(image, "image");
        this.resourceArgs = resourceArgs == null ? List.of() : List.copyOf(resourceArgs);
        this.resourceLimits = resourceLimits == null ? "" : resourceLimits;
    }

    DockerAttemptExecutor(
            Path projectDir,
            boolean runtimeMode,
            String scorerFingerprint,
            String dockerCommand,
            String image,
            List<String> resourceArgs,
            String resourceLimits) {
        this.runtime = null;
        this.projectDir = projectDir == null ? Path.of("").toAbsolutePath().normalize() : projectDir.toAbsolutePath().normalize();
        this.runtimeMode = runtimeMode;
        this.scorerFingerprint = scorerFingerprint == null ? "(none)" : scorerFingerprint;
        this.dockerCommand = Objects.requireNonNull(dockerCommand, "dockerCommand");
        this.image = Objects.requireNonNull(image, "image");
        this.resourceArgs = resourceArgs == null ? List.of() : List.copyOf(resourceArgs);
        this.resourceLimits = resourceLimits == null ? "" : resourceLimits;
    }

    @Override
    public AttemptExecution execute(EvalCaseLoader.LoadedCase loaded, int attemptNumber) {
        Path workspace = DockerEvalExecutionEnvironment.seededWorkspace(loaded);
        ProviderMaterialization providerMaterialization = !runtimeMode
                ? ProviderMaterialization.noModel()
                : providerMaterialization();
        ProcessSupervisor.Outcome info = dockerInfo();
        if (!info.succeeded()) {
            DockerEvalExecutionEnvironment environment = new DockerEvalExecutionEnvironment(
                    workspace,
                    loaded.verifyScript(),
                    dockerCommand,
                    image,
                    resourceArgs);
            EvalRunManifest manifest = EvalRunManifestFactory.capture(
                    projectDir(),
                    loaded,
                    runtime,
                    environment.isolationProfile(),
                    scorerFingerprint,
                    Instant.now(),
                    EvalBackendManifest.dockerPhase1(
                            image,
                            "(unavailable)",
                            resourceLimits,
                            runtimeMode ? "proxy-key-custody+container-temp-home" : "none-self-test",
                            projectExtensionMounts()));
            return failureExecution(
                    environment,
                    manifest,
                    "docker backend unavailable: `docker info` failed\n" + info.output());
        }
        DockerEgressProxySession egressProxy = null;
        if (runtimeMode || !providerMaterialization.proxyRoutes().isEmpty()) {
            egressProxy = new DockerEgressProxySession(
                    dockerCommand,
                    image,
                    resourceArgs,
                    projectDir(),
                    providerMaterialization.proxyRoutes(),
                    providerMaterialization.proxyEnv(),
                    providerMaterialization.secrets());
        }
        DockerEvalExecutionEnvironment environment = new DockerEvalExecutionEnvironment(
                workspace,
                loaded.verifyScript(),
                dockerCommand,
                image,
                resourceArgs,
                egressProxy);
        Path inputDir = null;
        Path outputDir = null;
        try {
            inputDir = Files.createTempDirectory("mada-eval-docker-input-");
            outputDir = Files.createTempDirectory("mada-eval-docker-output-");
            Instant startedAt = Instant.now();
            EvalBackendManifest backendManifest = egressProxy == null
                    ? EvalBackendManifest.dockerPhase1(
                            image,
                            imageDigest(),
                            resourceLimits,
                            "none-self-test",
                            projectExtensionMounts())
                    : EvalBackendManifest.dockerPhase2(
                            image,
                            imageDigest(),
                            resourceLimits,
                            "proxy-key-custody+container-temp-home",
                            projectExtensionMounts());
            EvalRunManifest manifest = EvalRunManifestFactory.capture(
                    projectDir(),
                    loaded,
                    runtime,
                    environment.isolationProfile(),
                    scorerFingerprint,
                    startedAt,
                    backendManifest);

            EvalAttemptInputJson input = attemptInput(loaded, attemptNumber, providerMaterialization);
            Path inputFile = inputDir.resolve("attempt.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(inputFile.toFile(), input);
            String fallbackOutcomeJson = MAPPER.writeValueAsString(completedNoModelOutcome(loaded));
            ProcessSupervisor.Outcome run = runContainer(
                    workspace,
                    inputDir,
                    outputDir,
                    fallbackOutcomeJson,
                    input.executionMode(),
                    providerMaterialization.agentEnvArgs(),
                    providerMaterialization.agentEnv(),
                    egressProxy == null ? List.of() : List.of("--network", egressProxy.networkName()),
                    RunBudget.from(loaded.evalCase()));
            if (!run.succeeded()) {
                return failureExecution(
                        environment,
                        manifest,
                        "docker attempt container failed with exit " + run.exitCode() + "\n" + run.output());
            }

            Path outcomeFile = outputDir.resolve("outcome.json");
            if (!Files.isRegularFile(outcomeFile)) {
                return failureExecution(environment, manifest, "docker attempt did not write output/outcome.json");
            }
            AttemptExecutionResultJson dto;
            try {
                dto = redact(MAPPER.readValue(outcomeFile.toFile(), AttemptExecutionResultJson.class),
                        providerMaterialization.secrets());
            } catch (IOException e) {
                return failureExecution(
                        environment,
                        manifest,
                        "docker attempt wrote invalid output/outcome.json: " + e.getMessage());
            }
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            return new AttemptExecution(
                    environment,
                    manifest,
                    outcome(dto),
                    trace(dto),
                    durationMs);
        } catch (IOException e) {
            environment.close();
            throw new UncheckedIOException("docker attempt failed for " + loaded.evalCase().id(), e);
        } catch (RuntimeException e) {
            environment.close();
            throw e;
        } finally {
            DockerEvalExecutionEnvironment.deleteTree(inputDir);
            DockerEvalExecutionEnvironment.deleteTree(outputDir);
        }
    }

    private ProcessSupervisor.Outcome dockerInfo() {
        return new ProcessSupervisor().run(
                List.of(dockerCommand, "info"),
                Path.of("").toAbsolutePath(),
                DOCKER_INFO_TIMEOUT,
                64 * 1024);
    }

    private ProcessSupervisor.Outcome runContainer(
            Path workspace,
            Path inputDir,
            Path outputDir,
            String fallbackOutcomeJson,
            String executionMode,
            List<String> providerEnvArgs,
            Map<String, String> providerEnv,
            List<String> networkArgs,
            RunBudget budget) {
        List<String> envArgs = new java.util.ArrayList<>();
        envArgs.add("-e");
        envArgs.add("MADA_EVAL_OUTCOME=" + fallbackOutcomeJson);
        envArgs.add("-e");
        envArgs.add("MADA_EVAL_MODE=" + executionMode);
        envArgs.addAll(providerEnvArgs);
        List<String> volumes = List.of(
                "-v", workspace.toAbsolutePath() + ":/workspace:rw",
                "-v", inputDir.toAbsolutePath() + ":/input:ro",
                "-v", outputDir.toAbsolutePath() + ":/output:rw",
                "-v", projectDir().resolve("target/MadaCode.jar").toAbsolutePath() + ":/app/MadaCode.jar:ro");
        String script = "if command -v java >/dev/null 2>&1; then "
                + "java -cp /app/MadaCode.jar madacode.eval.EvalAttemptMain "
                + "--input /input/attempt.json --workspace /workspace --output /output/outcome.json; "
                + "elif [ \"$MADA_EVAL_MODE\" = \"no-model\" ]; then "
                + "printf '%s' \"$MADA_EVAL_OUTCOME\" > /output/outcome.json; "
                + "else echo 'java is required for runtime eval attempts' >&2; exit 127; fi";
        List<String> command = DockerRunCommand.shell(
                dockerCommand,
                image,
                resourceArgs,
                networkArgs,
                envArgs,
                volumes,
                "/workspace",
                script);
        return new ProcessSupervisor().run(
                command,
                Path.of("").toAbsolutePath(),
                budget.caseTimeout().plus(DOCKER_RUN_GRACE),
                budget.maxProcessOutputBytes(),
                providerEnv);
    }

    private AttemptExecution failureExecution(
            DockerEvalExecutionEnvironment environment,
            EvalRunManifest manifest,
            String detail) {
        ModeLauncher.LaunchOutcome outcome = new ModeLauncher.LaunchOutcome(
                EvalResult.ExecutionStatus.CRASHED,
                RunMetrics.ZERO,
                "INFRA_ERROR",
                detail,
                "",
                false);
        return new AttemptExecution(environment, manifest, outcome, null, 0);
    }

    private EvalAttemptInputJson attemptInput(
            EvalCaseLoader.LoadedCase loaded,
            int attemptNumber,
            ProviderMaterialization providerMaterialization) {
        return new EvalAttemptInputJson(
                EvalAttemptInputJson.SCHEMA_VERSION,
                loaded.evalCase(),
                attemptNumber,
                runtime == null ? EvalAttemptInputJson.MODE_NO_MODEL : EvalAttemptInputJson.MODE_RUNTIME,
                "/workspace",
                providerMaterialization.configJson(),
                List.of("docker-phase2-proxied-egress"));
    }

    private ProviderMaterialization providerMaterialization() {
        Path providersFile = Path.of(System.getProperty("user.home")).resolve(".mada/providers.json");
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(providersFile.toFile());
            ObjectNode sanitizedRoot = root.deepCopy();
            com.fasterxml.jackson.databind.JsonNode providers = sanitizedRoot.path("providers");
            if (!providers.isArray()) {
                throw new IllegalStateException("providers.json must contain a providers array");
            }
            List<DockerEgressProxyConfigJson.ProviderRouteJson> proxyRoutes = new java.util.ArrayList<>();
            Map<String, String> proxyEnv = new java.util.LinkedHashMap<>();
            List<String> secrets = new java.util.ArrayList<>();
            int index = 0;
            for (com.fasterxml.jackson.databind.JsonNode provider : providers) {
                if (!(provider instanceof ObjectNode providerObject)) {
                    throw new IllegalStateException("providers[" + index + "] must be an object");
                }
                String token = providerToken(providerObject, "providers[" + index + "]");
                String providerName = text(providerObject, "name", "providers[" + index + "]");
                String baseUrl = text(providerObject, "baseUrl", "providers[" + index + "]");
                String envName = "MADA_EVAL_PROXY_PROVIDER_TOKEN_" + index;
                providerObject.remove("authToken");
                providerObject.put("authToken", "proxied-provider-token");
                providerObject.put("baseUrl", "http://mada-egress-proxy:8080/provider/" + index);
                proxyRoutes.add(new DockerEgressProxyConfigJson.ProviderRouteJson(
                        index,
                        providerName,
                        baseUrl,
                        envName));
                proxyEnv.put(envName, token);
                secrets.add(token);
                index++;
            }
            return new ProviderMaterialization(
                    MAPPER.writeValueAsString(sanitizedRoot),
                    List.of(),
                    Map.of(),
                    List.copyOf(proxyRoutes),
                    Map.copyOf(proxyEnv),
                    List.copyOf(secrets));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to prepare provider config for docker eval", e);
        }
    }

    private static String providerToken(ObjectNode providerObject, String context) {
        com.fasterxml.jackson.databind.JsonNode literal = providerObject.get("authToken");
        com.fasterxml.jackson.databind.JsonNode envRef = providerObject.get("authTokenEnv");
        if (literal != null && envRef != null) {
            throw new IllegalStateException(context + " must declare only one of authToken or authTokenEnv");
        }
        if (literal != null) {
            return text(providerObject, "authToken", context);
        }
        String envName = text(providerObject, "authTokenEnv", context);
        String value = firstNonBlank(System.getenv(envName), System.getProperty(envName));
        if (value == null) {
            throw new IllegalStateException("Environment variable '" + envName
                    + "' declared by authTokenEnv in " + context + " is not set");
        }
        return value;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field, String context) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Missing or blank '" + field + "' in " + context);
        }
        return value.asText();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record ProviderMaterialization(
            String configJson,
            List<String> agentEnvArgs,
            Map<String, String> agentEnv,
            List<DockerEgressProxyConfigJson.ProviderRouteJson> proxyRoutes,
            Map<String, String> proxyEnv,
            List<String> secrets) {
        static ProviderMaterialization noModel() {
            return new ProviderMaterialization("", List.of(), Map.of(), List.of(), Map.of(), List.of());
        }

        ProviderMaterialization {
            configJson = configJson == null ? "" : configJson;
            agentEnvArgs = agentEnvArgs == null ? List.of() : List.copyOf(agentEnvArgs);
            agentEnv = agentEnv == null ? Map.of() : Map.copyOf(agentEnv);
            proxyRoutes = proxyRoutes == null ? List.of() : List.copyOf(proxyRoutes);
            proxyEnv = proxyEnv == null ? Map.of() : Map.copyOf(proxyEnv);
            secrets = secrets == null ? List.of() : List.copyOf(secrets);
        }
    }

    private AttemptExecutionResultJson completedNoModelOutcome(EvalCaseLoader.LoadedCase loaded) {
        EvalCase evalCase = loaded.evalCase();
        return new AttemptExecutionResultJson(
                AttemptExecutionResultJson.SCHEMA_VERSION,
                evalCase.id(),
                evalCase.mode(),
                EvalResult.ExecutionStatus.COMPLETED.name(),
                "COMPLETED",
                "docker no-model attempt completed",
                "",
                EvalReportJson.metricsJson(RunMetrics.ZERO),
                null,
                true,
                new AttemptExecutionResultJson.TraceJson(
                        List.of(),
                        List.of(),
                        List.of(evalCase.instruction()),
                        List.of(),
                        "",
                        EvalReportJson.metricsJson(RunMetrics.ZERO)),
                List.of("phase1-self-test-no-model"));
    }

    private ModeLauncher.LaunchOutcome outcome(AttemptExecutionResultJson dto) {
        return new ModeLauncher.LaunchOutcome(
                executionStatus(dto.executionStatus()),
                metrics(dto.metrics()),
                dto.terminalSummary(),
                dto.detail(),
                dto.finalText(),
                dto.quiescent(),
                apiFailure(dto.apiFailure()));
    }

    private AttemptExecutionResultJson redact(AttemptExecutionResultJson dto, List<String> secrets) {
        if (dto == null || secrets == null || secrets.isEmpty()) {
            return dto;
        }
        return new AttemptExecutionResultJson(
                dto.schemaVersion(),
                dto.caseId(),
                dto.mode(),
                dto.executionStatus(),
                redact(dto.terminalSummary(), secrets),
                redact(dto.detail(), secrets),
                redact(dto.finalText(), secrets),
                dto.metrics(),
                redact(dto.apiFailure(), secrets),
                dto.quiescent(),
                redact(dto.trace(), secrets),
                dto.diagnostics().stream().map(value -> redact(value, secrets)).toList());
    }

    private AttemptExecutionResultJson.ApiFailureJson redact(
            AttemptExecutionResultJson.ApiFailureJson apiFailure,
            List<String> secrets) {
        if (apiFailure == null) {
            return null;
        }
        return new AttemptExecutionResultJson.ApiFailureJson(
                apiFailure.type(),
                apiFailure.retryable(),
                apiFailure.httpStatus(),
                redact(apiFailure.detail(), secrets));
    }

    private AttemptExecutionResultJson.TraceJson redact(
            AttemptExecutionResultJson.TraceJson trace,
            List<String> secrets) {
        if (trace == null) {
            return null;
        }
        return new AttemptExecutionResultJson.TraceJson(
                trace.invocations().stream()
                        .map(invocation -> new AttemptExecutionResultJson.InvocationJson(
                                invocation.name(),
                                redact(invocation.inputJson(), secrets),
                                redact(invocation.resultJson(), secrets),
                                invocation.accessEvidence(),
                                invocation.phase(),
                                invocation.ordinal()))
                        .toList(),
                trace.fileEffects(),
                trace.userTurns().stream().map(value -> redact(value, secrets)).toList(),
                trace.assistantTurns().stream().map(value -> redact(value, secrets)).toList(),
                redact(trace.finalText(), secrets),
                trace.metrics());
    }

    private String redact(String value, List<String> secrets) {
        String redacted = value == null ? "" : value;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, "[REDACTED_PROVIDER_TOKEN]");
            }
        }
        return redacted;
    }

    private ExecutionTrace trace(AttemptExecutionResultJson dto) {
        AttemptExecutionResultJson.TraceJson trace = dto.trace();
        if (trace == null) {
            return null;
        }
        return new ExecutionTrace(
                trace.invocations().stream()
                        .map(invocation -> new ToolInvocation(
                                invocation.name(),
                                invocation.inputJson(),
                                invocation.resultJson(),
                                invocation.accessEvidence(),
                                ToolInvocation.Phase.valueOf(invocation.phase()),
                                invocation.ordinal()))
                        .toList(),
                trace.fileEffects(),
                trace.userTurns(),
                trace.assistantTurns(),
                trace.finalText(),
                metrics(trace.metrics()));
    }

    private ApiFailureClassification apiFailure(AttemptExecutionResultJson.ApiFailureJson apiFailure) {
        if (apiFailure == null) {
            return null;
        }
        return new ApiFailureClassification(
                ApiErrorType.valueOf(apiFailure.type()),
                apiFailure.retryable(),
                apiFailure.httpStatus(),
                apiFailure.detail());
    }

    private EvalResult.ExecutionStatus executionStatus(String status) {
        if (status == null || status.isBlank()) {
            return EvalResult.ExecutionStatus.CRASHED;
        }
        return EvalResult.ExecutionStatus.valueOf(status.toUpperCase(Locale.ROOT));
    }

    private RunMetrics metrics(EvalReportJson.MetricsJson metrics) {
        if (metrics == null) {
            return RunMetrics.ZERO;
        }
        return EvalReportJson.runMetrics(metrics);
    }

    private String imageDigest() {
        ProcessSupervisor.Outcome inspect = new ProcessSupervisor().run(
                List.of(dockerCommand, "image", "inspect", image,
                        "--format", "{{index .RepoDigests 0}}"),
                Path.of("").toAbsolutePath(),
                Duration.ofSeconds(5),
                4096);
        if (!inspect.succeeded()) {
            return "(unavailable)";
        }
        String digest = inspect.output().strip();
        return digest.isBlank() || digest.contains("can't evaluate field")
                ? "(unavailable)"
                : digest;
    }

    private List<String> projectExtensionMounts() {
        Path mada = projectDir().resolve(".mada");
        return Files.isDirectory(mada) ? List.of(".mada:ro") : List.of();
    }

    private Path projectDir() {
        return projectDir;
    }
}
