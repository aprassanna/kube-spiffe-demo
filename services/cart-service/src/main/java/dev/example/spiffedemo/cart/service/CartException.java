package dev.example.spiffedemo.cart.service;

import org.springframework.http.HttpStatus;

/** Error with the HTTP status it should map to. Rendered as RFC 9457 problem details. */
public class CartException extends RuntimeException {

    private final HttpStatus status;

    public CartException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public static CartException conflict(String message) {
        return new CartException(HttpStatus.CONFLICT, message, null);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
