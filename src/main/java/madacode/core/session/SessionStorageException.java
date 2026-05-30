package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;

public final class SessionStorageException extends RuntimeException {

    public SessionStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public SessionStorageException(String message) {
        super(message);
    }
}
