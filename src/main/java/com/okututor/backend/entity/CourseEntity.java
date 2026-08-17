package com.okututor.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "courses")
public class CourseEntity {

  @Id
  private String id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "teacher_id", nullable = false)
  private UserEntity teacher;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, length = 4000)
  private String description;

  @Column(nullable = false)
  private String days;

  @Column(name = "specific_days")
  private String specificDays;

  @Column(name = "group_size", nullable = false)
  private String groupSize;

  @Column(name = "location_type", nullable = false)
  private String locationType;

  @Column(nullable = false)
  private Integer experience;

  @Column(name = "price_per_hour", nullable = false)
  private Double pricePerHour;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "course", orphanRemoval = true)
  private List<ReviewEntity> reviews = new ArrayList<>();

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID().toString();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public UserEntity getTeacher() {
    return teacher;
  }

  public void setTeacher(UserEntity teacher) {
    this.teacher = teacher;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getDays() {
    return days;
  }

  public void setDays(String days) {
    this.days = days;
  }

  public String getSpecificDays() {
    return specificDays;
  }

  public void setSpecificDays(String specificDays) {
    this.specificDays = specificDays;
  }

  public String getGroupSize() {
    return groupSize;
  }

  public void setGroupSize(String groupSize) {
    this.groupSize = groupSize;
  }

  public String getLocationType() {
    return locationType;
  }

  public void setLocationType(String locationType) {
    this.locationType = locationType;
  }

  public Integer getExperience() {
    return experience;
  }

  public void setExperience(Integer experience) {
    this.experience = experience;
  }

  public Double getPricePerHour() {
    return pricePerHour;
  }

  public void setPricePerHour(Double pricePerHour) {
    this.pricePerHour = pricePerHour;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public List<ReviewEntity> getReviews() {
    return reviews;
  }

  public void setReviews(List<ReviewEntity> reviews) {
    this.reviews = reviews;
  }

  public Double getAverageRating() {
    if (reviews == null || reviews.isEmpty()) {
      return 0.0;
    }
    return reviews.stream().mapToInt(ReviewEntity::getRating).average().orElse(0.0);
  }
}

