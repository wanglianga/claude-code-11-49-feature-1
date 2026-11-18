package com.clinic.mpi.service;

public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(message);
    }
}
