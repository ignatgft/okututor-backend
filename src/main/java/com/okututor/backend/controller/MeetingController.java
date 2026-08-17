package com.okututor.backend.controller;

import com.okututor.backend.dto.meeting.CreateMeetingRequest;
import com.okututor.backend.dto.meeting.CreateMeetingResponse;
import com.okututor.backend.security.JwtUserPrincipal;
import com.okututor.backend.service.MeetingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeetingController {

  private final MeetingService meetingService;

  public MeetingController(MeetingService meetingService) {
    this.meetingService = meetingService;
  }

  @PostMapping("/create-meeting/")
  public CreateMeetingResponse create(@Valid @RequestBody CreateMeetingRequest request,
      @AuthenticationPrincipal JwtUserPrincipal principal) {
    return meetingService.create(request, principal);
  }
}

