package com.okututor.backend.tutors.dto;

import jakarta.validation.constraints.NotBlank;

/** плоское тело POST /tutors/applications (TutorApplicationRequest из docs/mapping.md #19). */
public record TutorApplicationSubmitRequest(
        @NotBlank(message = "Name is required") String full_name,
        String phone,
        String location,
        Integer experience_years,
        String experience_description,
        String education,
        String subjects,
        String languages,
        String bio,
        String id_document_name
) {}
