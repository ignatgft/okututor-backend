package com.okututor.backend.schedule;

import com.okututor.backend.course.Course;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Регулярное расписание занятий по подтверждённой заявке (ровно одно на заявку).
 * Статусы: DRAFT → PROPOSED → CONFIRMED → COMPLETED (или CANCELLED).
 * Конкретные встречи (Booking + связанный Lesson) материализуются из CONFIRMED расписания.
 */
@Entity
@Table(name = "schedules")
public class Schedule {

    public enum Status { DRAFT, PROPOSED, CONFIRMED, CANCELLED, COMPLETED }
    public enum Format { ONLINE, OFFLINE }
    public enum Frequency { WEEKLY, BIWEEKLY, DAILY, CUSTOM }

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private Enrollment application;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tutor_id", nullable = false)
    private User tutor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Format format = Format.ONLINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", length = 20)
    private com.okututor.backend.lesson.LocationType locationType;

    @Column(name = "location_address", columnDefinition = "text")
    private String locationAddress;

    @Column(name = "location_details", columnDefinition = "text")
    private String locationDetails;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 50)
    private String timezone = "UTC";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Frequency frequency = Frequency.WEEKLY;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScheduleSlot> slots = new ArrayList<>();

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
        return userId != null && (userId.equals(getStudentId()) || userId.equals(getTutorId()));
    }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public Enrollment getApplication() { return application; }
    public void setApplication(Enrollment application) { this.application = application; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public User getStudent() { return student; }
    public void setStudent(User student) { this.student = student; }
    public User getTutor() { return tutor; }
    public void setTutor(User tutor) { this.tutor = tutor; }
    public UUID getStudentId() { return student != null ? student.getId() : null; }
    public UUID getTutorId() { return tutor != null ? tutor.getId() : null; }
    public Format getFormat() { return format; }
    public void setFormat(Format format) { this.format = format; }
    public com.okututor.backend.lesson.LocationType getLocationType() { return locationType; }
    public void setLocationType(com.okututor.backend.lesson.LocationType locationType) { this.locationType = locationType; }
    public String getLocationAddress() { return locationAddress; }
    public void setLocationAddress(String locationAddress) { this.locationAddress = locationAddress; }
    public String getLocationDetails() { return locationDetails; }
    public void setLocationDetails(String locationDetails) { this.locationDetails = locationDetails; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Frequency getFrequency() { return frequency; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public List<ScheduleSlot> getSlots() { return slots; }
    public void setSlots(List<ScheduleSlot> slots) { this.slots = slots; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}