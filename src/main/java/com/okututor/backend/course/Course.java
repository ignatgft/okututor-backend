package com.okututor.backend.course;

import com.okututor.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "courses")
public class Course {

    public enum Status { DRAFT, PENDING, APPROVED, REJECTED }

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 100)
    private String subject;

    @Column(length = 100)
    private String category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "price_per_hour", nullable = false, precision = 12, scale = 2)
    private BigDecimal pricePerHour = BigDecimal.ZERO;

    @Column(nullable = false, length = 8)
    private String currency = "KGS";

    @Convert(converter = LocationTypeConverter.class)
    @Column(name = "location_type", nullable = false, length = 10)
    private LocationType locationType = LocationType.ONLINE;

    @Convert(converter = GroupSizeConverter.class)
    @Column(name = "group_size", nullable = false, length = 12)
    private GroupSize groupSize = GroupSize.INDIVIDUAL;

    /** csv как присылает фронт: weekdays / weekends / specific */
    @Column(columnDefinition = "text")
    private String days;

    @Column(name = "specific_days", columnDefinition = "text")
    private String specificDays;

    @Column
    private Integer experience;

    @Column(name = "max_students")
    private Integer maxStudents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    /** URL оптимизированной обложки в object storage (media pipeline). */
    @Column(name = "cover_url", columnDefinition = "text")
    private String coverUrl;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "reviews_count", nullable = false)
    private int reviewsCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** optimistic locking: защита агрегатов рейтинга от lost updates */
    @Version
    private Long version;

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

    public enum LocationType { ONLINE, OFFLINE }

    public enum GroupSize { INDIVIDUAL, GROUP }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public User getTeacher() { return teacher; }
    public void setTeacher(User teacher) { this.teacher = teacher; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPricePerHour() { return pricePerHour; }
    public void setPricePerHour(BigDecimal pricePerHour) { this.pricePerHour = pricePerHour; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocationType getLocationType() { return locationType; }
    public void setLocationType(LocationType locationType) { this.locationType = locationType; }
    public GroupSize getGroupSize() { return groupSize; }
    public void setGroupSize(GroupSize groupSize) { this.groupSize = groupSize; }
    public String getDays() { return days; }
    public void setDays(String days) { this.days = days; }
    public String getSpecificDays() { return specificDays; }
    public void setSpecificDays(String specificDays) { this.specificDays = specificDays; }
    public Integer getExperience() { return experience; }
    public void setExperience(Integer experience) { this.experience = experience; }
    public Integer getMaxStudents() { return maxStudents; }
    public void setMaxStudents(Integer maxStudents) { this.maxStudents = maxStudents; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public BigDecimal getAverageRating() { return averageRating; }
    public void setAverageRating(BigDecimal averageRating) { this.averageRating = averageRating; }
    public int getReviewsCount() { return reviewsCount; }
    public void setReviewsCount(int reviewsCount) { this.reviewsCount = reviewsCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
