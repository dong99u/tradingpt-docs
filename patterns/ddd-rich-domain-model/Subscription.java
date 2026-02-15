package com.example.tradingpt.domain.subscription.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import com.example.tradingpt.domain.paymentmethod.entity.PaymentMethod;
import com.example.tradingpt.domain.subscription.enums.Status;
import com.example.tradingpt.domain.subscription.enums.SubscriptionType;
import com.example.tradingpt.domain.subscriptionplan.entity.SubscriptionPlan;
import com.example.tradingpt.domain.user.entity.Customer;
import com.example.tradingpt.global.common.BaseEntity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Subscription Entity - Rich Domain Model Example
 *
 * Demonstrates:
 * 1. @DynamicUpdate: Only changed fields included in UPDATE query
 * 2. Business methods: Encapsulate state transitions and domain rules
 * 3. JPA Dirty Checking: No explicit save() needed within @Transactional
 * 4. Tell, Don't Ask: Clients call behavior methods, not getters+setters
 */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@DynamicInsert
@DynamicUpdate  // Only changed fields in UPDATE query
@Table(name = "subscription")
public class Subscription extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscription_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_plan_id")
    private SubscriptionPlan subscriptionPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_method_id")
    private PaymentMethod paymentMethod;

    private BigDecimal subscribedPrice;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.ACTIVE;

    private LocalDate currentPeriodStart;
    private LocalDate currentPeriodEnd;
    private LocalDate nextBillingDate;
    private LocalDate lastBillingDate;
    private LocalDateTime cancelledAt;

    @Builder.Default
    private Integer paymentFailedCount = 0;

    private LocalDateTime lastPaymentFailedAt;

    // ========================
    // Business Methods
    // ========================

    /**
     * Update billing cycle dates.
     * JPA dirty checking automatically persists changes at transaction commit.
     */
    public void updateBillingDates(LocalDate nextBillingDate,
                                   LocalDate currentPeriodStart,
                                   LocalDate currentPeriodEnd) {
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
        this.nextBillingDate = nextBillingDate;
    }

    /**
     * Record a payment failure. Increments counter and timestamps the failure.
     */
    public void incrementPaymentFailure() {
        this.paymentFailedCount++;
        this.lastPaymentFailedAt = LocalDateTime.now();
    }

    /**
     * Reset payment failure state after a successful payment.
     */
    public void resetPaymentFailure(LocalDate lastBillingDate) {
        this.paymentFailedCount = 0;
        this.lastPaymentFailedAt = null;
        this.lastBillingDate = lastBillingDate;
    }

    /**
     * Transition subscription status with automatic cancellation timestamp.
     */
    public void updateStatus(Status newStatus) {
        this.status = newStatus;
        if (newStatus == Status.CANCELLED) {
            this.cancelledAt = LocalDateTime.now();
        }
    }

    /**
     * Update payment method (e.g., card re-registration).
     */
    public void updatePaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}
