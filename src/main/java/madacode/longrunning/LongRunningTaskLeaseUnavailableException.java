package madacode.longrunning;

public final class LongRunningTaskLeaseUnavailableException extends RuntimeException {

    LongRunningTaskLeaseUnavailableException(String message) {
        super(message);
    }
}
