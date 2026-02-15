package com.example.tradingpt.patterns.jpa;

/**
 * JPA Dirty Checking: Before vs After
 *
 * This file demonstrates the transformation from the Builder Recreation
 * anti-pattern to proper JPA Dirty Checking with Entity business methods.
 */
public class DirtyCheckingExample {

    // ================================================================
    // BEFORE: Builder Recreation Anti-Pattern (119 lines per method)
    // ================================================================

    /**
     * Problem: Recreates entire entity to change 2 fields.
     *
     * Issues:
     * - Memory waste: unnecessary object allocation
     * - Performance: UPDATE includes ALL 17 fields
     * - Maintenance: new fields require updating ALL builder blocks
     * - Bypasses JPA Dirty Checking and @DynamicUpdate benefits
     */
    /*
    @Transactional
    public Subscription incrementPaymentFailureCount_BEFORE(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(...));

        int newFailureCount = subscription.getPaymentFailedCount() + 1;

        // Anti-pattern: copy ALL fields just to change 2
        Subscription updated = Subscription.builder()
            .id(subscription.getId())
            .customer(subscription.getCustomer())
            .subscriptionPlan(subscription.getSubscriptionPlan())
            .paymentMethod(subscription.getPaymentMethod())
            .subscribedPrice(subscription.getSubscribedPrice())
            .status(subscription.getStatus())
            .currentPeriodStart(subscription.getCurrentPeriodStart())
            .currentPeriodEnd(subscription.getCurrentPeriodEnd())
            .nextBillingDate(subscription.getNextBillingDate())
            .lastBillingDate(subscription.getLastBillingDate())
            .cancelledAt(subscription.getCancelledAt())
            .cancellationReason(subscription.getCancellationReason())
            .paymentFailedCount(newFailureCount)          // actual change
            .lastPaymentFailedAt(LocalDateTime.now())     // actual change
            .subscriptionType(subscription.getSubscriptionType())
            .promotionNote(subscription.getPromotionNote())
            .build();

        return subscriptionRepository.save(updated);  // unnecessary save()
    }
    */

    // ================================================================
    // AFTER: JPA Dirty Checking with Entity Business Methods (5 lines)
    // ================================================================

    /**
     * Solution: Delegate to Entity business method.
     *
     * How it works:
     * 1. Entity loaded within @Transactional → enters "managed" state
     * 2. Entity method mutates internal fields
     * 3. At transaction commit, JPA compares current vs original snapshot
     * 4. Only changed fields generate UPDATE query (with @DynamicUpdate)
     * 5. No explicit save() needed for managed entities
     */
    /*
    @Transactional
    public Subscription incrementPaymentFailureCount_AFTER(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(...));

        subscription.incrementPaymentFailure();  // Entity business method

        return subscription;  // JPA auto-generates UPDATE at commit
    }
    */

    // ================================================================
    // Entity Business Method (inside Subscription.java)
    // ================================================================

    /**
     * The entity encapsulates the state change logic:
     *
     * public void incrementPaymentFailure() {
     *     this.paymentFailedCount++;
     *     this.lastPaymentFailedAt = LocalDateTime.now();
     * }
     *
     * Combined with @DynamicUpdate, this generates:
     * UPDATE subscription
     *   SET payment_failed_count = ?, last_payment_failed_at = ?
     *   WHERE subscription_id = ?
     *
     * Instead of updating all 17 fields.
     */

    // ================================================================
    // When IS save() needed?
    // ================================================================

    /**
     * save() is ONLY required for:
     *
     * 1. NEW entities (not yet persisted):
     *    Subscription sub = Subscription.builder()...build();
     *    subscriptionRepository.save(sub);  // Required!
     *
     * 2. After bulk operations (bypasses persistence context):
     *    subscriptionRepository.bulkUpdateStatus(Status.CANCELLED);
     *    entityManager.flush();
     *    entityManager.clear();
     *
     * 3. Outside @Transactional (not recommended):
     *    subscription.updateStatus(Status.ACTIVE);
     *    subscriptionRepository.save(subscription);  // Required without tx
     */
}
