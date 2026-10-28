package io.budget.watchdog.ingest;

/**
 * A message that can never be processed: malformed JSON, a missing field, an unsupported schema version. Retrying
 * can't fix it, so the error handler sends it straight to the dead-letter topic.
 */
public class InvalidTransactionException extends RuntimeException {

    public InvalidTransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
