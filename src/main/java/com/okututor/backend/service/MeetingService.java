package com.okututor.backend.service;

import com.okututor.backend.dto.meeting.CreateMeetingRequest;
import com.okututor.backend.dto.meeting.CreateMeetingResponse;
import com.okututor.backend.security.JwtUserPrincipal;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MeetingService {

  private final String baseUrl;

  public MeetingService(@Value("${app.meeting.base-url:https://meet.okututor.local}") String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public CreateMeetingResponse create(CreateMeetingRequest request, JwtUserPrincipal principal) {
    String meetingId = UUID.randomUUID().toString();
    String joinUrl = baseUrl + "/join/" + meetingId;
    String meetingUrl = baseUrl + "/meeting/" + meetingId + "?topic=" +
        URLEncoder.encode(request.topic(), StandardCharsets.UTF_8);
    return new CreateMeetingResponse(meetingId, meetingUrl, joinUrl);
  }
}
