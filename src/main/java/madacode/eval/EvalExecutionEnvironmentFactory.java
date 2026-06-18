package madacode.eval;

@FunctionalInterface
public interface EvalExecutionEnvironmentFactory {
    EvalExecutionEnvironment create(EvalCaseLoader.LoadedCase loaded);
}
