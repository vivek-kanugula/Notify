package com.notify.outbox.service;

import com.notify.common.model.OutboxStatus;
import com.notify.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class OutboxQueryServiceImpl implements OutboxQueryService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxQueryServiceImpl(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount() {
        return outboxEventRepository.countByStatus(OutboxStatus.PENDING);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> oldestPendingCreatedAt() {
        return Optional.ofNullable(outboxEventRepository.oldestPendingCreatedAt());
    }
}
