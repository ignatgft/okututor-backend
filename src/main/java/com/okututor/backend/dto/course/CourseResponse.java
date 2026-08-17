package com.okututor.backend.dto.course;

import com.okututor.backend.entity.CourseEntity;

public record CourseResponse(
    String id,
    String teacherId,
    String title,
    String description,
    String days,
    String specificDays,
    String groupSize,
    String locationType,
    Integer experience,
    Double pricePerHour,
    Double averageRating
) {

  public static CourseResponse fromEntity(CourseEntity course) {
    return new CourseResponse(
        course.getId(),
        course.getTeacher().getId(),
        course.getTitle(),
        course.getDescription(),
        course.getDays(),
        course.getSpecificDays(),
        course.getGroupSize(),
        course.getLocationType(),
        course.getExperience(),
        course.getPricePerHour(),
        course.getAverageRating()
    );
  }
}

