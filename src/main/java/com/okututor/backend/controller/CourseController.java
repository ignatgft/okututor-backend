package com.okututor.backend.controller;

import com.okututor.backend.dto.course.CourseCreateRequest;
import com.okututor.backend.dto.course.CourseCreateResponse;
import com.okututor.backend.dto.course.CourseResponse;
import com.okututor.backend.dto.course.CreateReviewRequest;
import com.okututor.backend.dto.course.CourseReviewResponse;
import com.okututor.backend.dto.course.CourseReviewCreateResponse;
import com.okututor.backend.service.CourseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CourseController {

  private final CourseService courseService;

  public CourseController(CourseService courseService) {
    this.courseService = courseService;
  }

  @GetMapping("/courses")
  public List<CourseResponse> allCourses() {
    return courseService.getAllCourses();
  }

  @PostMapping("/courses")
  public CourseCreateResponse createCourse(@Valid @RequestBody CourseCreateRequest request,
      @AuthenticationPrincipal com.okututor.backend.security.JwtUserPrincipal principal) {
    return courseService.createCourse(request, principal);
  }

  @GetMapping("/courses/{courseId}")
  public CourseResponse courseById(@PathVariable String courseId) {
    return courseService.getCourse(courseId);
  }

  @DeleteMapping("/courses/{courseId}")
  public ResponseEntity<Void> deleteCourse(@PathVariable String courseId,
      @AuthenticationPrincipal com.okututor.backend.security.JwtUserPrincipal principal) {
    courseService.deleteCourse(courseId, principal);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/courses/{courseId}/reviews")
  public List<CourseReviewResponse> reviews(@PathVariable String courseId) {
    return courseService.getReviews(courseId);
  }

  @PostMapping("/courses/{courseId}/reviews")
  public ResponseEntity<CourseReviewCreateResponse> addReview(@PathVariable String courseId,
      @Valid @RequestBody CreateReviewRequest request,
      @AuthenticationPrincipal com.okututor.backend.security.JwtUserPrincipal principal) {
    return ResponseEntity.status(HttpStatus.CREATED).body(courseService.addReview(courseId, request, principal));
  }

  @GetMapping("/users/{userId}/courses")
  public List<CourseResponse> coursesByTeacher(@PathVariable String userId) {
    return courseService.getCoursesByTeacher(userId);
  }
}

