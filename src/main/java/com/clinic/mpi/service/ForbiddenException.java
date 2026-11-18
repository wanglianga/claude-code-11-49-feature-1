package com.clinic.mpi.service;

/** 访问被拒绝（如普通运营用户读取审核个案明细）。 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(message);
    }
}
