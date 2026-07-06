package madacode.eval;

/** Executes the agent side of one eval attempt and leaves scoring to {@link EvalRunner}. */
interface AttemptExecutor {

    AttemptExecution execute(EvalCaseLoader.LoadedCase loaded, int attemptNumber);
}
