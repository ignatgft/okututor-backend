package com.okututor.backend.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO-проекция кандидатского search-запроса (спека #25): один JOIN с users
 * вместо lazy-загрузки teacher на каждую строку (N+1), без загрузки entity-графа.
 * Поля покрывают контракт CourseResponse + скоринг-сигналы из SQL.
 */
public interface CourseSearchProjection {

    UUID getId();

    String getTitle();

    String getSubject();

    String getCategory();

    String getDescription();

    BigDecimal getPricePerHour();

    String getCurrency();

    String getLocationType();

    String getGroupSize();

    String getDays();

    String getSpecificDays();

    Integer getExperience();

    Integer getMaxStudents();

    String getStatus();

    String getRejectionReason();

    String getCoverUrl();

    BigDecimal getAverageRating();

    Integer getReviewsCount();

    Instant getCreatedAt();

    UUID getTeacherId();

    String getTeacherFirstName();

    String getTeacherLastName();

    String getTeacherEmail();

    /** ts_rank_cd по ru/en векторам (0..1), 0 при отсутствии текстового сигнала. */
    double getTextScore();

    /** pg_trgm similarity названия к запросу (0..1). */
    double getTrgmScore();

    /** 1 при точном совпадении названия с запросом. */
    Integer getExactMatch();
}
