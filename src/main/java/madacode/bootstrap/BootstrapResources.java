package madacode.bootstrap;

import madacode.cli.Repl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class BootstrapResources implements AutoCloseable {

    private final Deque<ManagedCloseable> closeables = new ArrayDeque<>();
    private final Deque<ManagedCloseable> failureOnlyCloseables = new ArrayDeque<>();
    private final Deque<ManagedCloseable> transferredCloseables = new ArrayDeque<>();
    private boolean transferred;

    <T extends AutoCloseable> T own(T resource) {
        Objects.requireNonNull(resource, "resource");
        if (transferred) {
            throw new IllegalStateException("resources already transferred");
        }
        closeables.push(new ManagedCloseable(resource));
        return resource;
    }

    void closeOnBootstrapFailure(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        if (transferred) {
            throw new IllegalStateException("resources already transferred");
        }
        failureOnlyCloseables.push(new ManagedCloseable(resource));
    }

    void transferTo(Repl repl) {
        Objects.requireNonNull(repl, "repl");
        if (transferred) {
            throw new IllegalStateException("resources already transferred");
        }
        transferred = true;
        failureOnlyCloseables.clear();
        while (!closeables.isEmpty()) {
            ManagedCloseable closeable = closeables.removeFirst();
            transferredCloseables.addLast(closeable);
            repl.addShutdownTarget(closeable);
        }
    }

    @Override
    public void close() {
        if (transferred) {
            while (!transferredCloseables.isEmpty()) {
                transferredCloseables.removeFirst().close();
            }
            return;
        }
        while (!closeables.isEmpty()) {
            closeables.removeFirst().close();
        }
        while (!failureOnlyCloseables.isEmpty()) {
            failureOnlyCloseables.removeFirst().close();
        }
    }

    void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::close, "mada-bootstrap-shutdown"));
    }

    private static final class ManagedCloseable implements AutoCloseable {
        private final AutoCloseable target;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ManagedCloseable(AutoCloseable target) {
            this.target = target;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                target.close();
            } catch (Exception ignored) {
            }
        }
    }
}
