package com.okututor.backend.service;

import com.okututor.backend.entity.CourseEntity;
import com.okututor.backend.entity.ReviewEntity;
import com.okututor.backend.entity.UserEntity;
import com.okututor.backend.repository.CourseRepository;
import com.okututor.backend.repository.ReviewRepository;
import com.okututor.backend.repository.UserRepository;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SeedDataService {

  @Bean
  public CommandLineRunner seedDatabase(UserRepository userRepository,
      CourseRepository courseRepository,
      ReviewRepository reviewRepository,
      PasswordEncoder passwordEncoder) {
    return args -> {
      if (userRepository.count() > 0) {
        return;
      }

      UserEntity teacher = new UserEntity();
      teacher.setEmail("teacher@okututor.dev");
      teacher.setPasswordHash(passwordEncoder.encode("Password123!"));
      teacher.setFullName("Aibek Teacher");
      teacher.setLocation("Bishkek");
      teacher.setBio("Experienced math tutor for school and university students.");
      teacher.setTelegram("https://t.me/okututor_teacher");
      teacher.setInstagram("https://instagram.com/okututor_teacher");
      teacher.setWhatsapp("https://wa.me/996700000000");
      teacher.setAvatar("https://ui-avatars.com/api/?name=Aibek+Teacher&background=0D8ABC&color=fff");
      teacher.setRole("ROLE_TEACHER");
      teacher = userRepository.save(teacher);

      UserEntity student = new UserEntity();
      student.setEmail("student@okututor.dev");
      student.setPasswordHash(passwordEncoder.encode("Password123!"));
      student.setFullName("Demo Student");
      student.setLocation("Bishkek");
      student.setAvatar("https://ui-avatars.com/api/?name=Demo+Student&background=1D4ED8&color=fff");
      student = userRepository.save(student);

      CourseEntity math = new CourseEntity();
      math.setTeacher(teacher);
      math.setTitle("Mathematics for beginners");
      math.setDescription("Prepare for school exams with a friendly and structured approach.");
      math.setDays("weekdays");
      math.setSpecificDays(null);
      math.setGroupSize("individual");
      math.setLocationType("online");
      math.setExperience(7);
      math.setPricePerHour(500.0);
      math = courseRepository.save(math);

      CourseEntity physics = new CourseEntity();
      physics.setTeacher(teacher);
      physics.setTitle("Physics intensive");
      physics.setDescription("Practice problems and exam preparation for physics.");
      physics.setDays("specific");
      physics.setSpecificDays("Monday,Wednesday,Friday");
      physics.setGroupSize("group");
      physics.setLocationType("offline");
      physics.setExperience(5);
      physics.setPricePerHour(700.0);
      physics = courseRepository.save(physics);

      ReviewEntity review = new ReviewEntity();
      review.setCourse(math);
      review.setStudent(student);
      review.setRating(5);
      review.setComment("Great explanations and a very friendly teacher.");
      reviewRepository.save(review);

      reviewRepository.flush();
      courseRepository.flush();
      userRepository.flush();
    };
  }
}

