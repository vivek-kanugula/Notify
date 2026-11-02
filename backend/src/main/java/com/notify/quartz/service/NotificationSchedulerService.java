package com.notify.quartz.service;

import com.notify.quartz.job.ReleaseScheduledNotificationJob;
import com.notify.quartz.job.RetryDueNotificationJob;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class NotificationSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSchedulerService.class);

    public static final String RELEASE_GROUP = "release-scheduled";
    public static final String RETRY_GROUP = "retry-due";

    private final Scheduler scheduler;

    public NotificationSchedulerService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void scheduleNotificationRelease(UUID tenantId, UUID notificationId, Instant scheduledAt, int generation) {
        scheduleNotificationRelease(tenantId, notificationId, scheduledAt, generation, null);
    }

    public void scheduleNotificationRelease(UUID tenantId, UUID notificationId, Instant scheduledAt, int generation, String traceparent) {
        JobKey jobKey = JobKey.jobKey("release-" + notificationId, RELEASE_GROUP);
        TriggerKey triggerKey = TriggerKey.triggerKey("release-trg-" + notificationId, RELEASE_GROUP);

        var jobDetailBuilder = JobBuilder.newJob(ReleaseScheduledNotificationJob.class)
                .withIdentity(jobKey)
                .usingJobData("tenantId", tenantId.toString())
                .usingJobData("notificationId", notificationId.toString())
                .usingJobData("expectedGeneration", generation);

        if (traceparent != null && !traceparent.isBlank()) {
            jobDetailBuilder.usingJobData("traceparent", traceparent);
        }

        JobDetail jobDetail = jobDetailBuilder
                .storeDurably(false)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobDetail)
                .startAt(Date.from(scheduledAt))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                .build();

        try {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Scheduled release for notification {} at {} (generation {})", notificationId, scheduledAt, generation);
        } catch (SchedulerException e) {
            log.error("Failed to schedule release for notification {}: {}", notificationId, e.getMessage(), e);
            throw new RuntimeException("Failed to schedule notification release", e);
        }
    }

    public boolean cancelScheduledRelease(UUID notificationId) {
        TriggerKey triggerKey = TriggerKey.triggerKey("release-trg-" + notificationId, RELEASE_GROUP);
        JobKey jobKey = JobKey.jobKey("release-" + notificationId, RELEASE_GROUP);

        try {
            boolean unscheduled = scheduler.unscheduleJob(triggerKey);
            scheduler.deleteJob(jobKey);
            log.info("Unscheduled release trigger for notification {}: unscheduled={}", notificationId, unscheduled);
            return unscheduled;
        } catch (SchedulerException e) {
            log.warn("Failed to cancel scheduled release for notification {}: {}", notificationId, e.getMessage());
            return false;
        }
    }

    public void scheduleNotificationRetry(UUID tenantId, UUID notificationId, Instant nextRetryAt, int generation) {
        scheduleNotificationRetry(tenantId, notificationId, nextRetryAt, generation, null);
    }

    public void scheduleNotificationRetry(UUID tenantId, UUID notificationId, Instant nextRetryAt, int generation, String traceparent) {
        JobKey jobKey = JobKey.jobKey("retry-" + notificationId, RETRY_GROUP);
        TriggerKey triggerKey = TriggerKey.triggerKey("retry-trg-" + notificationId, RETRY_GROUP);

        var jobDetailBuilder = JobBuilder.newJob(RetryDueNotificationJob.class)
                .withIdentity(jobKey)
                .usingJobData("tenantId", tenantId.toString())
                .usingJobData("notificationId", notificationId.toString())
                .usingJobData("expectedGeneration", generation);

        if (traceparent != null && !traceparent.isBlank()) {
            jobDetailBuilder.usingJobData("traceparent", traceparent);
        }

        JobDetail jobDetail = jobDetailBuilder
                .storeDurably(false)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobDetail)
                .startAt(Date.from(nextRetryAt))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                .build();

        try {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Scheduled retry for notification {} at {} (generation {})", notificationId, nextRetryAt, generation);
        } catch (SchedulerException e) {
            log.error("Failed to schedule retry for notification {}: {}", notificationId, e.getMessage(), e);
            throw new RuntimeException("Failed to schedule notification retry", e);
        }
    }

    public boolean cancelRetryTrigger(UUID notificationId) {
        TriggerKey triggerKey = TriggerKey.triggerKey("retry-trg-" + notificationId, RETRY_GROUP);
        JobKey jobKey = JobKey.jobKey("retry-" + notificationId, RETRY_GROUP);

        try {
            boolean unscheduled = scheduler.unscheduleJob(triggerKey);
            scheduler.deleteJob(jobKey);
            log.info("Unscheduled retry trigger for notification {}: unscheduled={}", notificationId, unscheduled);
            return unscheduled;
        } catch (SchedulerException e) {
            log.warn("Failed to cancel retry trigger for notification {}: {}", notificationId, e.getMessage());
            return false;
        }
    }

    public boolean hasReleaseTrigger(UUID notificationId) {
        try {
            TriggerKey triggerKey = TriggerKey.triggerKey("release-trg-" + notificationId, RELEASE_GROUP);
            return scheduler.checkExists(triggerKey);
        } catch (SchedulerException e) {
            log.warn("Error checking existence of release trigger for notification {}: {}", notificationId, e.getMessage());
            return false;
        }
    }

    public boolean hasRetryTrigger(UUID notificationId) {
        try {
            TriggerKey triggerKey = TriggerKey.triggerKey("retry-trg-" + notificationId, RETRY_GROUP);
            return scheduler.checkExists(triggerKey);
        } catch (SchedulerException e) {
            log.warn("Error checking existence of retry trigger for notification {}: {}", notificationId, e.getMessage());
            return false;
        }
    }
}
