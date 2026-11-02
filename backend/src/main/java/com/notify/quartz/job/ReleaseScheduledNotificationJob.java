package com.notify.quartz.job;

import com.notify.notification.service.NotificationService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@DisallowConcurrentExecution
public class ReleaseScheduledNotificationJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ReleaseScheduledNotificationJob.class);

    private final NotificationService notificationService;

    public ReleaseScheduledNotificationJob(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        var dataMap = context.getMergedJobDataMap();
        String tenantIdStr = dataMap.getString("tenantId");
        String notificationIdStr = dataMap.getString("notificationId");
        int expectedGeneration = dataMap.getInt("expectedGeneration");

        if (tenantIdStr == null || notificationIdStr == null) {
            log.error("ReleaseScheduledNotificationJob missing required job data: tenantId={}, notificationId={}", tenantIdStr, notificationIdStr);
            return;
        }

        UUID tenantId = UUID.fromString(tenantIdStr);
        UUID notificationId = UUID.fromString(notificationIdStr);

        try {
            notificationService.releaseScheduledNotification(tenantId, notificationId, expectedGeneration);
        } catch (Exception e) {
            log.error("Failed to release scheduled notification {} for tenant {}: {}", notificationId, tenantId, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
