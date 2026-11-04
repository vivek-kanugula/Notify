package com.notify.template.dto;

import java.util.List;
import java.util.UUID;

public record DeleteVersionsResponse(
        UUID templateId,
        List<Integer> deletedVersions,
        int count
) {}

