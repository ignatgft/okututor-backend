package com.okututor.backend.lesson;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveKitWebhookEventRepository extends JpaRepository<LiveKitWebhookEvent, String> {
}
