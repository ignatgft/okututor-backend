package com.okututor.backend.common.error;

/**
 * коды ошибок, распознаваемые фронтом (errorMapper.js + auth.api.js).
 */
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // коды подтверждения email / сброса пароля (auth.api.js mapAuthError)
    public static final String INVALID_CODE = "INVALID_CODE";
    public static final String VERIFICATION_CODE_EXPIRED = "VERIFICATION_CODE_EXPIRED";
    public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";

    // доменные коды для понятных 403 (не 500)
    public static final String MEETING_NOT_AVAILABLE = "MEETING_NOT_AVAILABLE";
    public static final String REVIEW_NOT_ALLOWED = "REVIEW_NOT_ALLOWED";

    // ----- доменные коды workflow курс → заявка → расписание → занятия -----
    public static final String APPLICATION_NOT_FOUND = "APPLICATION_NOT_FOUND";
    public static final String INVALID_APPLICATION_STATE = "INVALID_APPLICATION_STATE";
    public static final String SCHEDULE_NOT_AVAILABLE = "SCHEDULE_NOT_AVAILABLE";
    public static final String SCHEDULE_CONFLICT = "SCHEDULE_CONFLICT";
    public static final String LESSON_CONFLICT = "LESSON_CONFLICT";
    public static final String INVALID_TIMEZONE = "INVALID_TIMEZONE";
    public static final String INVALID_DATE = "INVALID_DATE";
    public static final String NOT_APPLICATION_OWNER = "NOT_APPLICATION_OWNER";
    public static final String NOT_COURSE_OWNER = "NOT_COURSE_OWNER";

    private ErrorCodes() {
    }
}
