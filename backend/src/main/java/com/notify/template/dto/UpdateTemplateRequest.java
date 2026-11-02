package com.notify.template.dto;

import jakarta.validation.constraints.Size;

public record UpdateTemplateRequest(
        @Size(max = 128, message = "Name must be at most 128 characters")
        String name,

        @Size(max = 512, message = "Description must be at most 512 characters")
        String description
) {}
