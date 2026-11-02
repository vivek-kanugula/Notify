package com.notify.template.dto;

import com.notify.common.model.ContentType;
import com.notify.common.model.TemplateVersionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TemplateVersionResponse(
        UUID id,
        UUID templateId,
        Integer versionNumber,
        TemplateVersionStatus status,
        String subject,
        String body,
        ContentType contentType,
        List<String> variables,
        Instant createdAt,
        Instant updatedAt
) {}
