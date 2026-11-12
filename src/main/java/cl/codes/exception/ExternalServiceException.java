package cl.codes.exception;

import java.io.IOException;

public class ExternalServiceException extends IOException {
    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
