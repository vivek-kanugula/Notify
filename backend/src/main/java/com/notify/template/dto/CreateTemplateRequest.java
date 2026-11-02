package com.notify.template.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTemplateRequest(
        @NotBlank(message = "Template name is required")
        @Size(max = 128, message = "Name must be at most 128 characters")
        String name,

        @NotNull(message = "Channel is required")
        Channel channel,

        @Size(max = 512, message = "Description must be at most 512 characters")
        String description,

        @Size(max = 512, message = "Subject must be at most 512 characters")
        String subject,

        @NotBlank(message = "Body is required")
        String body,

        ContentType contentType
) {}
