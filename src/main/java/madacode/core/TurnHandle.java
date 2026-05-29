package madacode.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public record TurnHandle(
        String turnId,
        CompletableFuture<TurnResult> result,
        Consumer<String> cancel) {}
