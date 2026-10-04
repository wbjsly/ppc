package com.erp.common;

public class ServiceException extends RuntimeException {

    private int code = 500;

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    public int getCode() {
        return code;
    }
}
