package dev.example.spiffedemo.cart.api;

import dev.example.spiffedemo.cart.service.CartException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CartExceptionHandler {

    @ExceptionHandler(CartException.class)
    public ProblemDetail handle(CartException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
    }
}
