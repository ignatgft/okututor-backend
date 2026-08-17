package com.okututor.backend.dto.course;

import com.okututor.backend.entity.ReviewEntity;
import java.time.Instant;

public record CourseReviewResponse(
    String id,
    String studentId,
    Integer rating,
    String comment,
    Instant createdAt
) {

  public static CourseReviewResponse fromEntity(ReviewEntity review) {
    return new CourseReviewResponse(
        review.getId(),
        review.getStudent().getId(),
        review.getRating(),
        review.getComment(),
        review.getCreatedAt()
    );
  }
}

