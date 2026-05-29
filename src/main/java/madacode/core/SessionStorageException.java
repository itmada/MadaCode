package madacode.core;

public final class SessionStorageException extends RuntimeException {

    public SessionStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public SessionStorageException(String message) {
        super(message);
    }
}
