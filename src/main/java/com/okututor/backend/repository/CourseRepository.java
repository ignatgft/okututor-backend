package com.okututor.backend.repository;

import com.okututor.backend.entity.CourseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<CourseEntity, String> {
  List<CourseEntity> findByTeacher_Id(String teacherId);
}

