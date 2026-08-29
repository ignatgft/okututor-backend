package com.okututor.backend.tutors;

import com.okututor.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tutor_applications")
public class TutorApplication {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    private UUID id;

    /**
     * Обычная связь с независимым суррогатным PK (как во всех остальных entity):
     * прежний вариант с @MapsId не заполнял id при persist — INSERT уходил
     * с id = NULL и падал по NOT NULL constraint.
     */
    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(length = 200)
    private String fullName;

    @Column(length = 40)
    private String phone;

    @Column(length = 255)
    private String location;

    @Column(name = "experience_years")
    private Integer experienceYears;

    @Column(name = "experience_description", columnDefinition = "text")
    private String experienceDescription;

    @Column(length = 500)
    private String education;

    /** список предметов через запятую, как его присылает фронт */
    @Column(columnDefinition = "text")
    private String subjects;

    @Column(columnDefinition = "text")
    private String languages;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "id_document_name", length = 255)
    private String idDocumentName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

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

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Integer getExperienceYears() { return experienceYears; }
    public void setExperienceYears(Integer experienceYears) { this.experienceYears = experienceYears; }
    public String getExperienceDescription() { return experienceDescription; }
    public void setExperienceDescription(String v) { this.experienceDescription = v; }
    public String getEducation() { return education; }
    public void setEducation(String education) { this.education = education; }
    public String getSubjects() { return subjects; }
    public void setSubjects(String subjects) { this.subjects = subjects; }
    public String getLanguages() { return languages; }
    public void setLanguages(String languages) { this.languages = languages; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getIdDocumentName() { return idDocumentName; }
    public void setIdDocumentName(String idDocumentName) { this.idDocumentName = idDocumentName; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
