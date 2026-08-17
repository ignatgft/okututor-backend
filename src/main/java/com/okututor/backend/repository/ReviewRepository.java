package com.okututor.backend.repository;

import com.okututor.backend.entity.ReviewEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<ReviewEntity, String> {
  List<ReviewEntity> findByCourse_IdOrderByCreatedAtDesc(String courseId);
}

