package com.okututor.backend.enrollment;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.ErrorCodes;
import com.okututor.backend.course.Course;
import com.okututor.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Заявка ученика на курс (= CourseApplication). Домен «заявка — расписание — занятия»:
 *
 * <pre>
 * PENDING → ACCEPTED → SCHEDULE_PENDING → SCHEDULE_PROPOSED → SCHEDULED → COMPLETED
 *    │         │              │                 │                  │          │
 *    ├→ NEEDS_INFO            └→ (turnera)      ├→ SCHEDULE_PENDING  └→ CANCELLED/EXPIRED/ COMPLETED
 *    └→ REJECTED / CANCELLED / EXPIRED          └→ CANCELLED/EXPIRED
 * </pre>
 *
 * Предпочтения ученика (days/время/формат) — это ПОЖЕЛАНИЯ, а не расписание:
 * реальные встречи порождаются только из подтверждённого Schedule.
 * COMPLETED — все занятия по SCHEDULED завершены (Lesson COMPLETED).
 */
@Entity
@Table(name = "enrollments")
public class Enrollment {

    public enum Status {
        PENDING, NEEDS_INFO, ACCEPTED, REJECTED,
        SCHEDULE_PENDING, SCHEDULE_PROPOSED, SCHEDULED, CANCELLED, EXPIRED, COMPLETED
    }

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /** тьютор курса, денормализован из course.teacher для прямых запросов. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tutor_id")
    private User tutor;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "preferred_schedule", columnDefinition = "text")
    private String preferredSchedule;

    @Column(name = "preferred_format", length = 10)
    private String preferredFormat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferred_days", columnDefinition = "jsonb")
    private List<String> preferredDays;

    @Column(name = "preferred_start_time")
    private LocalTime preferredStartTime;

    @Column(name = "preferred_end_time")
    private LocalTime preferredEndTime;

    @Column(length = 20)
    private String frequency;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // --- state machine ---

    private static final Map<Status, List<Status>> ALLOWED = Map.of(
            Status.PENDING, List.of(Status.NEEDS_INFO, Status.ACCEPTED, Status.REJECTED, Status.CANCELLED, Status.EXPIRED),
            Status.NEEDS_INFO, List.of(Status.PENDING, Status.ACCEPTED, Status.REJECTED, Status.CANCELLED, Status.EXPIRED),
            Status.ACCEPTED, List.of(Status.SCHEDULE_PENDING, Status.SCHEDULE_PROPOSED, Status.CANCELLED, Status.EXPIRED, Status.COMPLETED),
            Status.SCHEDULE_PENDING, List.of(Status.SCHEDULE_PROPOSED, Status.CANCELLED, Status.EXPIRED, Status.COMPLETED),
            Status.SCHEDULE_PROPOSED, List.of(Status.SCHEDULED, Status.SCHEDULE_PENDING, Status.CANCELLED, Status.EXPIRED, Status.COMPLETED, Status.SCHEDULE_PROPOSED),
            Status.SCHEDULED, List.of(Status.CANCELLED, Status.COMPLETED, Status.EXPIRED),
            Status.COMPLETED, List.of(),
            Status.REJECTED, List.of(),
            Status.CANCELLED, List.of(),
            Status.EXPIRED, List.of());

    /** активные («в работе») статусы: на них распространяется unique-индекс на пару (курс, студент). */
    public static List<Status> ACTIVE_STATUSES = List.of(
            Status.PENDING, Status.NEEDS_INFO, Status.ACCEPTED, Status.SCHEDULE_PENDING, Status.SCHEDULE_PROPOSED);

    private static final List<Status> STUDENT_CANCELABLE =
            List.of(Status.PENDING, Status.NEEDS_INFO, Status.ACCEPTED, Status.SCHEDULE_PENDING, Status.SCHEDULE_PROPOSED, Status.SCHEDULED);

    /**
     * Проверяемый бэкендом переход заявки. Незаконные переходы (REJECTED→ACCEPTED,
     * CANCELLED→SCHEDULED, ...) запрещены и не зависят от frontend.
     */
    public void transitionTo(Status target) {
        Status current = getStatus();
        List<Status> allowed = ALLOWED.getOrDefault(current, List.of());
        if (!allowed.contains(target)) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "Cannot move application from %s to %s".formatted(current.name(), target.name()));
        }
        setStatus(target);
    }

    /** заявку в этих статусах студент вправе отменить. */
    public boolean studentMayCancel() {
        return getStatus() != null && STUDENT_CANCELABLE.contains(getStatus());
    }

    /** переход в терминальное CANCELLED (с инвариантом «не из терминального»). */
    public void cancel() {
        transitionTo(Status.CANCELLED);
    }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public User getStudent() { return student; }
    public void setStudent(User student) { this.student = student; }
    public User getTutor() { return tutor; }
    public void setTutor(User tutor) { this.tutor = tutor; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getPreferredSchedule() { return preferredSchedule; }
    public void setPreferredSchedule(String preferredSchedule) { this.preferredSchedule = preferredSchedule; }
    public String getPreferredFormat() { return preferredFormat; }
    public void setPreferredFormat(String preferredFormat) { this.preferredFormat = preferredFormat; }
    public List<String> getPreferredDays() {
        return preferredDays == null ? List.of() : preferredDays;
    }
    public void setPreferredDays(List<String> preferredDays) {
        this.preferredDays = preferredDays == null || preferredDays.isEmpty() ? null : new ArrayList<>(preferredDays);
    }
    public LocalTime getPreferredStartTime() { return preferredStartTime; }
    public void setPreferredStartTime(LocalTime preferredStartTime) { this.preferredStartTime = preferredStartTime; }
    public LocalTime getPreferredEndTime() { return preferredEndTime; }
    public void setPreferredEndTime(LocalTime preferredEndTime) { this.preferredEndTime = preferredEndTime; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}