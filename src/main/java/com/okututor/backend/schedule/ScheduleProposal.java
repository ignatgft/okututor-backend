package com.okututor.backend.schedule;

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
 * Предложение расписания — отдельная история согласования: тьютор предлагает,
 * студент принимает / отклоняет / делает контрпцредложение. Устаревшие предложения
 * переводятся в SUPERSEDED, полная история сохраняется для таймлайна заявки.
 */
@Entity
@Table(name = "schedule_proposals")
public class ScheduleProposal {

    public enum Status { PENDING, ACCEPTED, REJECTED, SUPERSEDED, CANCELLED }

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private Enrollment application;

    /** формируемое расписание (создаётся/обновляется при первом предложении). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false, length = 50)
    private String timezone = "UTC";

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(columnDefinition = "text")
    private String message;

    @OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScheduleProposalSlot> slots = new ArrayList<>();

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

    public UUID getStudentId() {
        return application != null && application.getStudent() != null ? application.getStudent().getId() : null;
    }

    public UUID getTutorId() {
        return application != null && application.getTutor() != null ? application.getTutor().getId()
                : (application != null && application.getCourse() != null
                        && application.getCourse().getTeacher() != null
                                ? application.getCourse().getTeacher().getId() : null);
    }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public Enrollment getApplication() { return application; }
    public void setApplication(Enrollment application) { this.application = application; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<ScheduleProposalSlot> getSlots() { return slots; }
    public void setSlots(List<ScheduleProposalSlot> slots) { this.slots = slots; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}