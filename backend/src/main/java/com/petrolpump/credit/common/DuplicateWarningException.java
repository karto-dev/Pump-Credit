package com.petrolpump.credit.common;

/** A probable duplicate that a user may knowingly override by resubmitting with confirmation. */
public class DuplicateWarningException extends RuntimeException {

    private final String existingRef;

    public DuplicateWarningException(String message, String existingRef) {
        super(message);
        this.existingRef = existingRef;
    }

    public String existingRef() {
        return existingRef;
    }
}
