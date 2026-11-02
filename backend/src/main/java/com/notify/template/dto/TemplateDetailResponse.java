package com.notify.template.dto;

import com.notify.common.model.Channel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TemplateDetailResponse(
        UUID id,
        String name,
        Channel channel,
        String description,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt,
        List<TemplateVersionResponse> versions
) {}
