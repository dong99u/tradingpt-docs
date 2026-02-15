package com.example.tradingpt.domain.subscription.service.command;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.tradingpt.domain.subscription.entity.Subscription;
import com.example.tradingpt.domain.subscription.enums.Status;
import com.example.tradingpt.domain.subscription.exception.SubscriptionErrorStatus;
import com.example.tradingpt.domain.subscription.exception.SubscriptionException;
import com.example.tradingpt.domain.subscription.repository.SubscriptionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin Service Layer - Subscription Command Service
 *
 * Demonstrates:
 * 1. Service orchestrates, Entity owns logic
 * 2. No explicit save() calls - JPA Dirty Checking handles persistence
 * 3. Each method delegates to Entity business methods
 * 4. Service focuses on: transaction management, entity lookup, exception handling
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class SubscriptionCommandService {

    private final SubscriptionRepository subscriptionRepository;

    /**
     * Update next billing date and period.
     * Entity.updateBillingDates() encapsulates the state change.
     * No save() needed - managed entity changes auto-persist at commit.
     */
    public Subscription updateNextBillingDate(
        Long subscriptionId,
        LocalDate nextBillingDate,
        LocalDate currentPeriodStart,
        LocalDate currentPeriodEnd
    ) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(SubscriptionErrorStatus.SUBSCRIPTION_NOT_FOUND));

        subscription.updateBillingDates(nextBillingDate, currentPeriodStart, currentPeriodEnd);

        return subscription;  // No save() needed!
    }

    /**
     * Increment payment failure counter.
     * Entity handles counter increment + timestamp update internally.
     */
    public Subscription incrementPaymentFailureCount(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(SubscriptionErrorStatus.SUBSCRIPTION_NOT_FOUND));

        subscription.incrementPaymentFailure();

        return subscription;
    }

    /**
     * Reset failure count after successful payment.
     */
    public Subscription resetPaymentFailureCount(Long subscriptionId, LocalDate lastBillingDate) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(SubscriptionErrorStatus.SUBSCRIPTION_NOT_FOUND));

        subscription.resetPaymentFailure(lastBillingDate);

        return subscription;
    }

    /**
     * Update subscription status.
     * Entity.updateStatus() handles the CANCELLED timestamp logic.
     */
    public Subscription updateSubscriptionStatus(Long subscriptionId, Status status) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(SubscriptionErrorStatus.SUBSCRIPTION_NOT_FOUND));

        subscription.updateStatus(status);

        return subscription;
    }
}
