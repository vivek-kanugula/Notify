package com.notify.template.dto;

import com.notify.common.model.Channel;

import java.time.Instant;
import java.util.UUID;

public record TemplateResponse(
        UUID id,
        String name,
        Channel channel,
        String description,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt,
        TemplateVersionResponse currentVersion
) {}
