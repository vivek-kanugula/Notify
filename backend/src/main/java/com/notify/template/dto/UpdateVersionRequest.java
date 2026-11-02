package com.notify.template.dto;

import com.notify.common.model.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateVersionRequest(
        @Size(max = 512, message = "Subject must be at most 512 characters")
        String subject,

        @NotBlank(message = "Body is required")
        String body,

        ContentType contentType
) {}
