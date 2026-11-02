package com.notify.template.dto;

import java.util.Collections;
import java.util.Map;

public record PreviewTemplateRequest(
        Map<String, Object> variables
) {
    public PreviewTemplateRequest {
        if (variables == null) {
            variables = Collections.emptyMap();
        }
    }
}
