package com.example.tradingpt.domain.subscription.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.tradingpt.domain.subscription.service.RecurringPaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Recurring Payment Scheduler
 *
 * Executes daily at midnight to process automatic payments for
 * subscriptions whose billing date has arrived.
 *
 * ShedLock ensures exactly-once execution across multiple instances:
 * - lockAtMostFor: 24h safety timeout (prevents deadlock if instance crashes)
 * - lockAtLeastFor: 23h minimum hold (prevents duplicate runs within a day)
 *
 * The actual payment processing logic is delegated to RecurringPaymentService,
 * keeping the scheduler focused solely on timing and distributed coordination.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringPaymentScheduler {

    private final RecurringPaymentService recurringPaymentService;

    @Scheduled(cron = "0 0 0 * * *")  // Daily at midnight
    @SchedulerLock(
        name = "recurringPaymentScheduler",
        lockAtMostFor = "PT24H",   // Safety: release lock after 24h even if crashed
        lockAtLeastFor = "PT23H"   // Prevent: no re-execution within 23h
    )
    public void executeRecurringPayments() {
        log.info("=== Recurring Payment Scheduler Started ===");

        try {
            int processedCount = recurringPaymentService.processRecurringPayments();
            log.info("=== Recurring Payment Scheduler Complete: {} subscriptions processed ===",
                processedCount);
        } catch (Exception e) {
            log.error("=== Recurring Payment Scheduler Error ===", e);
        }
    }
}
