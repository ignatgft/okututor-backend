package com.okututor.backend.lesson;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.course.Course;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lessons")
public class Lesson {

    public enum Status {
        SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED,
        RESCHEDULE_PENDING, FORMAT_CHANGE_PENDING, LOCATION_CHANGE_PENDING, DURATION_CHANGE_PENDING,
        STUDENT_NO_SHOW, TUTOR_NO_SHOW, ISSUE
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @OneToOne
    @JoinColumn(name = "booking_id")
    private Booking booking;

    /** владелец действий над уроком */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Column(length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.SCHEDULED;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    /** источник урока — расписание (null для одноразовых). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", length = 20)
    private LocationType locationType;

    @Column(name = "location_address", columnDefinition = "text")
    private String locationAddress;

    @Column(name = "location_details", columnDefinition = "text")
    private String locationDetails;

    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    // ---- жизненный цикл: фактические времена ----
    @Column(name = "actual_start")
    private Instant actualStart;

    @Column(name = "actual_end")
    private Instant actualEnd;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by")
    private User startedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by")
    private User completedBy;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(length = 500)
    private String topic;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(columnDefinition = "text")
    private String homework;

    @Column(columnDefinition = "text")
    private String materials;

    @Column(columnDefinition = "text")
    private String links;

    @Column(length = 20)
    private String attendance;

    // ---- pending предложения (не применяются автоматически) ----
    @Column(name = "pending_start_at")
    private Instant pendingStartAt;

    @Column(name = "pending_end_at")
    private Instant pendingEndAt;

    @Column(name = "pending_reason", columnDefinition = "text")
    private String pendingReason;

    @Column(name = "pending_format", length = 10)
    private String pendingFormat;

    @Column(name = "pending_location_type", length = 20)
    @Enumerated(EnumType.STRING)
    private LocationType pendingLocationType;

    @Column(name = "pending_location_address", columnDefinition = "text")
    private String pendingLocationAddress;

    @Column(name = "pending_location_details", columnDefinition = "text")
    private String pendingLocationDetails;

    @Column(name = "pending_duration_minutes")
    private Integer pendingDurationMinutes;

    @Column(name = "pending_scope", length = 20)
    private String pendingScope; // SINGLE | FUTURE

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pending_proposed_by")
    private User pendingProposedBy;

    @Column(name = "pending_proposed_at")
    private Instant pendingProposedAt;

    @Version
    private Long version;

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

    public boolean involves(UUID userId) {
        return userId != null && (userId.equals(getTeacherId()) || userId.equals(getStudentId()));
    }

    public boolean isLive() {
        return getStatus() == Status.SCHEDULED || getStatus() == Status.IN_PROGRESS;
    }

    public boolean isPending() {
        return getStatus() == Status.RESCHEDULE_PENDING
                || getStatus() == Status.FORMAT_CHANGE_PENDING
                || getStatus() == Status.LOCATION_CHANGE_PENDING
                || getStatus() == Status.DURATION_CHANGE_PENDING;
    }

    public void markCancelled(User by, String reason) {
        setStatus(Status.CANCELLED);
        setCancelledBy(by);
        setCancelReason(reason);
        setCancelledAt(Instant.now());
        clearPending();
    }

    public void clearPending() {
        pendingStartAt = null;
        pendingEndAt = null;
        pendingReason = null;
        pendingFormat = null;
        pendingLocationType = null;
        pendingLocationAddress = null;
        pendingLocationDetails = null;
        pendingDurationMinutes = null;
        pendingScope = null;
        pendingProposedBy = null;
        pendingProposedAt = null;
    }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }
    public User getTeacher() { return teacher; }
    public void setTeacher(User teacher) { this.teacher = teacher; }
    public User getStudent() { return student; }
    public void setStudent(User student) { this.student = student; }
    public UUID getTeacherId() { return getTeacher() != null ? getTeacher().getId() : null; }
    public UUID getStudentId() { return getStudent() != null ? getStudent().getId() : null; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }
    public LocationType getLocationType() { return locationType; }
    public void setLocationType(LocationType locationType) { this.locationType = locationType; }
    public String getLocationAddress() { return locationAddress; }
    public void setLocationAddress(String locationAddress) { this.locationAddress = locationAddress; }
    public String getLocationDetails() { return locationDetails; }
    public void setLocationDetails(String locationDetails) { this.locationDetails = locationDetails; }
    public Integer getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Integer sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public User getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(User cancelledBy) { this.cancelledBy = cancelledBy; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public Instant getActualStart() { return actualStart; }
    public void setActualStart(Instant actualStart) { this.actualStart = actualStart; }
    public Instant getActualEnd() { return actualEnd; }
    public void setActualEnd(Instant actualEnd) { this.actualEnd = actualEnd; }
    public User getStartedBy() { return startedBy; }
    public void setStartedBy(User startedBy) { this.startedBy = startedBy; }
    public User getCompletedBy() { return completedBy; }
    public void setCompletedBy(User completedBy) { this.completedBy = completedBy; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getHomework() { return homework; }
    public void setHomework(String homework) { this.homework = homework; }
    public String getMaterials() { return materials; }
    public void setMaterials(String materials) { this.materials = materials; }
    public String getLinks() { return links; }
    public void setLinks(String links) { this.links = links; }
    public String getAttendance() { return attendance; }
    public void setAttendance(String attendance) { this.attendance = attendance; }
    public Instant getPendingStartAt() { return pendingStartAt; }
    public void setPendingStartAt(Instant pendingStartAt) { this.pendingStartAt = pendingStartAt; }
    public Instant getPendingEndAt() { return pendingEndAt; }
    public void setPendingEndAt(Instant pendingEndAt) { this.pendingEndAt = pendingEndAt; }
    public String getPendingReason() { return pendingReason; }
    public void setPendingReason(String pendingReason) { this.pendingReason = pendingReason; }
    public String getPendingFormat() { return pendingFormat; }
    public void setPendingFormat(String pendingFormat) { this.pendingFormat = pendingFormat; }
    public LocationType getPendingLocationType() { return pendingLocationType; }
    public void setPendingLocationType(LocationType pendingLocationType) { this.pendingLocationType = pendingLocationType; }
    public String getPendingLocationAddress() { return pendingLocationAddress; }
    public void setPendingLocationAddress(String pendingLocationAddress) { this.pendingLocationAddress = pendingLocationAddress; }
    public String getPendingLocationDetails() { return pendingLocationDetails; }
    public void setPendingLocationDetails(String pendingLocationDetails) { this.pendingLocationDetails = pendingLocationDetails; }
    public Integer getPendingDurationMinutes() { return pendingDurationMinutes; }
    public void setPendingDurationMinutes(Integer pendingDurationMinutes) { this.pendingDurationMinutes = pendingDurationMinutes; }
    public String getPendingScope() { return pendingScope; }
    public void setPendingScope(String pendingScope) { this.pendingScope = pendingScope; }
    public User getPendingProposedBy() { return pendingProposedBy; }
    public void setPendingProposedBy(User pendingProposedBy) { this.pendingProposedBy = pendingProposedBy; }
    public Instant getPendingProposedAt() { return pendingProposedAt; }
    public void setPendingProposedAt(Instant pendingProposedAt) { this.pendingProposedAt = pendingProposedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
