package com.okututor.backend.service;

import com.okututor.backend.dto.course.CourseCreateRequest;
import com.okututor.backend.dto.course.CourseCreateResponse;
import com.okututor.backend.dto.course.CourseResponse;
import com.okututor.backend.dto.course.CourseReviewCreateResponse;
import com.okututor.backend.dto.course.CourseReviewResponse;
import com.okututor.backend.dto.course.CreateReviewRequest;
import com.okututor.backend.entity.CourseEntity;
import com.okututor.backend.entity.ReviewEntity;
import com.okututor.backend.entity.UserEntity;
import com.okututor.backend.exception.ApiBadRequestException;
import com.okututor.backend.exception.ApiNotFoundException;
import com.okututor.backend.repository.CourseRepository;
import com.okututor.backend.repository.ReviewRepository;
import com.okututor.backend.repository.UserRepository;
import com.okututor.backend.security.JwtUserPrincipal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

  private final CourseRepository courseRepository;
  private final ReviewRepository reviewRepository;
  private final UserRepository userRepository;

  public CourseService(CourseRepository courseRepository, ReviewRepository reviewRepository, UserRepository userRepository) {
    this.courseRepository = courseRepository;
    this.reviewRepository = reviewRepository;
    this.userRepository = userRepository;
  }

  @Transactional(readOnly = true)
  public List<CourseResponse> getAllCourses() {
    return courseRepository.findAll().stream().map(CourseResponse::fromEntity).toList();
  }

  @Transactional(readOnly = true)
  public CourseResponse getCourse(String courseId) {
    return CourseResponse.fromEntity(findCourse(courseId));
  }

  @Transactional(readOnly = true)
  public List<CourseResponse> getCoursesByTeacher(String userId) {
    return courseRepository.findByTeacher_Id(userId).stream().map(CourseResponse::fromEntity).toList();
  }

  @Transactional
  public CourseCreateResponse createCourse(CourseCreateRequest request, JwtUserPrincipal principal) {
    String ownerId = request.userId();
    if (ownerId == null || ownerId.isBlank()) {
      if (principal != null) {
        ownerId = principal.getId();
      } else {
        throw new ApiBadRequestException("user_id is required");
      }
    }

    UserEntity teacher = userRepository.findById(ownerId)
        .orElseThrow(() -> new ApiNotFoundException("Teacher not found"));

    CourseEntity course = new CourseEntity();
    course.setTeacher(teacher);
    course.setTitle(request.title());
    course.setDescription(request.description());
    course.setDays(request.days());
    course.setSpecificDays(request.specificDays());
    course.setGroupSize(request.groupSize());
    course.setLocationType(request.locationType());
    course.setExperience(request.experience());
    course.setPricePerHour(request.pricePerHour());

    CourseEntity saved = courseRepository.save(course);
    return new CourseCreateResponse(saved.getId(), CourseResponse.fromEntity(saved));
  }

  @Transactional
  public void deleteCourse(String courseId, JwtUserPrincipal principal) {
    CourseEntity course = findCourse(courseId);
    ensureOwner(course, principal);
    courseRepository.delete(course);
  }

  @Transactional(readOnly = true)
  public List<CourseReviewResponse> getReviews(String courseId) {
    findCourse(courseId);
    return reviewRepository.findByCourse_IdOrderByCreatedAtDesc(courseId).stream()
        .map(CourseReviewResponse::fromEntity)
        .toList();
  }

  @Transactional
  public CourseReviewCreateResponse addReview(String courseId, CreateReviewRequest request, JwtUserPrincipal principal) {
    CourseEntity course = findCourse(courseId);
    UserEntity student = userRepository.findById(request.studentId())
        .orElseThrow(() -> new ApiNotFoundException("Student not found"));

    if (principal != null && !principal.getId().equals(student.getId())) {
      throw new ApiBadRequestException("student_id must match authenticated user");
    }
    if (course.getTeacher().getId().equals(student.getId())) {
      throw new ApiBadRequestException("Cannot review your own course");
    }

    ReviewEntity review = new ReviewEntity();
    review.setCourse(course);
    review.setStudent(student);
    review.setRating(request.rating());
    review.setComment(request.comment());

    ReviewEntity saved = reviewRepository.save(review);
    return new CourseReviewCreateResponse(saved.getId(), CourseReviewResponse.fromEntity(saved));
  }

  private CourseEntity findCourse(String courseId) {
    return courseRepository.findById(courseId)
        .orElseThrow(() -> new ApiNotFoundException("Course not found"));
  }

  private void ensureOwner(CourseEntity course, JwtUserPrincipal principal) {
    if (principal == null || !course.getTeacher().getId().equals(principal.getId())) {
      throw new ApiBadRequestException("Only the course owner can modify it");
    }
  }
}
