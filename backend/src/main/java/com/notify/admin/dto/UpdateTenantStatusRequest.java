package com.notify.admin.dto;

import com.notify.common.model.TenantStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTenantStatusRequest(
        @NotNull(message = "status is required")
        TenantStatus status,

        @NotBlank(message = "reason is required for status changes")
        @Size(min = 3, max = 512, message = "reason must be between 3 and 512 characters")
        String reason
) {}
