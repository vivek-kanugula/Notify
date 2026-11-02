package com.notify.template.dto;

import com.notify.common.model.ContentType;

public record RenderedTemplateResponse(
        String subject,
        String body,
        ContentType contentType
) {}
