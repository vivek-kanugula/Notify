package com.notify.template.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DeleteVersionsRequest(
        @NotEmpty(message = "Version numbers list cannot be empty")
        List<Integer> versionNumbers
) {}

