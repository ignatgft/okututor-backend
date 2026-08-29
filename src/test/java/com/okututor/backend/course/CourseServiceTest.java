package com.okututor.backend.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.course.dto.CourseCreateRequest;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class CourseServiceTest {

    private CourseRepository repository;
    private CourseService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(CourseRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CourseService(repository);
    }

    private User tutor() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("tutor@test.com");
        user.setFirstName("Test");
        user.setLastName("Tutor");
        user.setRole(Role.TUTOR);
        return user;
    }

    @Test
    void createMapsWizardPayload() {
        CourseCreateRequest request = new CourseCreateRequest(
                "Python Programming", "Start your programming journey", "IT", null,
                BigDecimal.valueOf(2500), "KGS", "online", "group",
                List.of("weekdays", "weekends"), null, 3, 10, "PENDING");

        service.create(tutor(), request);

        Course course = capturedCourse();
        assertThat(course.getDays()).isEqualTo("weekdays,weekends");
        assertThat(course.getLocationType()).isEqualTo(Course.LocationType.ONLINE);
        assertThat(course.getGroupSize()).isEqualTo(Course.GroupSize.GROUP);
        assertThat(course.getStatus()).isEqualTo(Course.Status.PENDING);
        assertThat(course.getPricePerHour().intValue()).isEqualTo(2500);
        assertThat(course.getMaxStudents()).isEqualTo(10);
    }

    private Course capturedCourse() {
        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        Mockito.verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createRequiresTitleAndSubject() {
        CourseCreateRequest missingTitle = new CourseCreateRequest(
                null, null, "Math", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(tutor(), missingTitle))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getFieldErrors())
                .satisfies(errors -> assertThat((java.util.Map<String, String>) errors).containsKey("title"));
    }

    @Test
    void onlyOwnerOrAdminCanModify() {
        User owner = tutor();
        Course course = new Course();
        course.setTeacher(owner);

        User stranger = tutor();

        assertThatThrownBy(() -> CourseService.requireOwnerOrAdmin(stranger, course))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("FORBIDDEN");
    }

    // ---------- view(): видимость + DTO mapping без lazy-прокси ----------

    private Course courseWithStatus(User teacher, Course.Status status) {
        Course c = new Course();
        c.setTeacher(teacher);
        c.setTitle("Algebra");
        c.setSubject("Math");
        c.setStatus(status);
        return c;
    }

    @Test
    void viewReturnsApprovedCourseToAnonymous() {
        UUID id = UUID.randomUUID();
        Course approved = courseWithStatus(tutor(), Course.Status.APPROVED);
        when(repository.findByIdWithTeacher(id)).thenReturn(java.util.Optional.of(approved));

        var response = service.view(id, null);

        assertThat(response.title()).isEqualTo("Algebra");
        assertThat(response.teacher_name()).isEqualTo("Test Tutor"); // данные teacher уже в DTO
        verify(repository).findByIdWithTeacher(id);                  // join fetch, а не findById
    }

    @Test
    void viewHidesDraftFromAnonymousAsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdWithTeacher(id))
                .thenReturn(java.util.Optional.of(courseWithStatus(tutor(), Course.Status.DRAFT)));

        assertThatThrownBy(() -> service.view(id, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void viewShowsDraftToOwnerButNotToStranger() {
        User owner = tutor();
        UUID id = UUID.randomUUID();
        when(repository.findByIdWithTeacher(id))
                .thenReturn(java.util.Optional.of(courseWithStatus(owner, Course.Status.DRAFT)));

        assertThat(service.view(id, owner).title()).isEqualTo("Algebra");

        assertThatThrownBy(() -> service.view(id, tutor()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void viewMissingCourseIsNotFound() {
        when(repository.findByIdWithTeacher(any(UUID.class)))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.view(UUID.randomUUID(), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NOT_FOUND");
    }
}
