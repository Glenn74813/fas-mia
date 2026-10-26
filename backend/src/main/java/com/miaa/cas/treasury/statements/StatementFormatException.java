package com.miaa.cas.treasury.statements;

/** The file is not a well-formed statement in the declared format; the message says where. */
public class StatementFormatException extends RuntimeException {

    public StatementFormatException(String message) {
        super(message);
    }
}
