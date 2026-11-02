package com.notify.template.dto;

import com.notify.common.model.ContentType;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateVersionRequest(
        @Positive(message = "cloneFromVersion must be a positive integer")
        Integer cloneFromVersion,

        @Size(max = 512, message = "Subject must be at most 512 characters")
        String subject,

        String body,

        ContentType contentType
) {
    public CreateVersionRequest(String subject, String body, ContentType contentType) {
        this(null, subject, body, contentType);
    }
}
