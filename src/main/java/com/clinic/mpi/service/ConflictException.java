package com.clinic.mpi.service;

/**
 * 冲突异常（HTTP 409）：图谱版本过期、候选对已被处理、快照状态不允许操作等。
 * 携带当前图谱版本，便于操作员刷新后重新检查图谱。
 */
public class ConflictException extends ApiException {

    private final Long currentGraphVersion;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Long currentGraphVersion) {
        super(message);
        this.currentGraphVersion = currentGraphVersion;
    }

    public Long currentGraphVersion() {
        return currentGraphVersion;
    }
}
