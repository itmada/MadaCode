package madacode.eval;

/** Result of running the agent side of an attempt, with the environment kept open for scoring. */
record AttemptExecution(
        EvalExecutionEnvironment environment,
        EvalRunManifest manifest,
        ModeLauncher.LaunchOutcome outcome,
        ExecutionTrace trace,
        long executionDurationMs) implements AutoCloseable {

    @Override
    public void close() {
        if (environment != null) {
            environment.close();
        }
    }
}
