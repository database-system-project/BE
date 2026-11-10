package com.databasesystem.hospital.common;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Object details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Object details() { return details; }

    public static ApiException badRequest() {
        return new ApiException(400, "INVALID_REQUEST", "요청 형식 또는 입력값을 확인하세요.");
    }

    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "대상이 없거나 접근할 수 없는 리소스입니다.");
    }

    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "이 작업을 수행할 권한이 없습니다.");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
