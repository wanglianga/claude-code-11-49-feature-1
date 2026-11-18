package com.clinic.mpi.service;

/** 业务异常基类，由全局异常处理器映射为 HTTP 响应。 */
public abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }
}
