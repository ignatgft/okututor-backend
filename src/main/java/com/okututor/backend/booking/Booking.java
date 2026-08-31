package com.okututor.backend.booking;

import com.okututor.backend.course.Course;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.schedule.Schedule;
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
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    public enum Status { PENDING, CONFIRMED, REJECTED, CANCELLED, COMPLETED, PROPOSED, RESCHEDULED, NO_SHOW }

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private Enrollment enrollment;

    /** источник конкретного занятия — подтверждённое расписание (null для одноразовых заявок). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public boolean isLive() {
        return getStatus() == Status.PENDING || getStatus() == Status.CONFIRMED || getStatus() == Status.RESCHEDULED;
    }

    public void markCancelled(User by, String reason) {
        setStatus(Status.CANCELLED);
        setCancelledBy(by);
        setCancelReason(reason);
        setCancelledAt(Instant.now());
    }

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

    public boolean involves(UUID userId) {
        return userId.equals(getStudentId()) || userId.equals(getTeacherId());
    }

    /**
     * Допустимые переходы:
     *  PENDING -> CONFIRMED | REJECTED | CANCELLED
     *  CONFIRMED -> COMPLETED | CANCELLED | RESCHEDULED | NO_SHOW
     *  PROPOSED -> CONFIRMED | REJECTED | CANCELLED
     *  RESCHEDULED -> COMPLETED | CANCELLED | NO_SHOW
     */
    public void transitionTo(Status target) {
        Status current = getStatus();
        boolean allowed = switch (target) {
            case CONFIRMED -> current == Status.PENDING || current == Status.PROPOSED;
            case REJECTED -> current == Status.PENDING || current == Status.PROPOSED;
            case COMPLETED -> current == Status.CONFIRMED || current == Status.RESCHEDULED;
            case CANCELLED -> current == Status.PENDING || current == Status.CONFIRMED
                    || current == Status.PROPOSED || current == Status.RESCHEDULED;
            case RESCHEDULED -> current == Status.CONFIRMED;
            case NO_SHOW -> current == Status.CONFIRMED || current == Status.RESCHEDULED;
            default -> false;
        };
        if (!allowed) {
            throw com.okututor.backend.common.error.ApiException.conflict(
                    "Cannot move booking from %s to %s".formatted(current.name(), target.name()));
        }
        setStatus(target);
    }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public User getStudent() { return student; }
    public void setStudent(User student) { this.student = student; }
    public User getTeacher() { return teacher; }
    public void setTeacher(User teacher) { this.teacher = teacher; }
    public Enrollment getEnrollment() { return enrollment; }
    public void setEnrollment(Enrollment enrollment) { this.enrollment = enrollment; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }
    public User getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(User cancelledBy) { this.cancelledBy = cancelledBy; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public UUID getStudentId() {
        return getStudent() != null ? getStudent().getId() : null;
    }
    public UUID getTeacherId() {
        return getTeacher() != null ? getTeacher().getId() : null;
    }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
