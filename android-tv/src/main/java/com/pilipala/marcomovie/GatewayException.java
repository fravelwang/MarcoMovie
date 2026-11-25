package com.pilipala.marcomovie;

/**
 * The single failure type of the source gateway: every engine timeout, dead site, unparsable or
 * empty answer is folded into one {@link Kind}, so callers can branch/log without knowing which
 * vendor exception was thrown.
 */
final class GatewayException extends Exception {
    enum Kind {
        /** The site did not answer inside its budget. */
        TIMEOUT,
        /** The site/engine is unreachable or failed to start. */
        DEAD,
        /** The site answered with something that could not be parsed. */
        PARSE,
        /** The site answered, but had nothing for this request. */
        EMPTY,
        /** Anything else; the message carries the detail. */
        OTHER
    }

    final Kind kind;

    GatewayException(Kind kind, String message) {
        super(message);
        this.kind = kind == null ? Kind.OTHER : kind;
    }

    GatewayException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? Kind.OTHER : kind;
    }
}
