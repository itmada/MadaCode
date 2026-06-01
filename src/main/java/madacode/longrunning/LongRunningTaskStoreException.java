package madacode.longrunning;

public final class LongRunningTaskStoreException extends RuntimeException {

    public LongRunningTaskStoreException(String message) {
        super(message);
    }

    public LongRunningTaskStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
