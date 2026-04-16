package com.mapping;

public class MappingOperationException extends RuntimeException {

    private final String code;

    public MappingOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MappingOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
