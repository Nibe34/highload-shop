package ua.highload.shop.domain;

public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
