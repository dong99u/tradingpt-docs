# Entity Relationship Diagram

## ERD Cloud

[View Full ERD on ERD Cloud](https://www.erdcloud.com/d/3SBFbay4FKzqudZxA)

## Domain Tables (20 Domains)

| Domain | Key Tables | Description |
|--------|-----------|-------------|
| Auth | `persistent_logins` | Authentication, OAuth2 (Kakao/Naver), SMS verification |
| User | `user`, `customer`, `trainer`, `admin` | User management with JPA JOINED inheritance |
| Subscription | `subscription` | Subscription lifecycle and recurring billing management |
| SubscriptionPlan | `subscription_plan` | Plan definitions (pricing, duration, features) |
| Payment | `payment` | Payment transaction records and history |
| PaymentMethod | `payment_method` | Card types, billing keys for recurring payment |
| FeedbackRequest | `feedback_request` | Customer trading feedback submissions |
| FeedbackResponse | `feedback_response` | Trainer feedback responses with best selection (max 4) |
| WeeklyTradingSummary | `weekly_trading_summary` | Weekly P&L performance analytics |
| MonthlyTradingSummary | `monthly_trading_summary` | Monthly performance aggregation |
| Lecture | `lecture` | Lecture management with scheduled opening |
| Chapter | `chapter` | Lecture chapter structure and content |
| LevelTest | `level_test` | User proficiency evaluation |
| Consultation | `consultation` | Consultation booking with status tracking |
| Review | `review` | User review management |
| Column | `column` | Content column articles |
| Complaint | `complaint` | Customer complaint handling workflow |
| Memo | `memo` | User-specific memo (1:1 with Customer) |
| InvestmentTypeHistory | `investment_type_history` | Investment type tracking (DAY/SWING) |
| Event | `event` | Platform event management |

## Key Relationships

### User Hierarchy (JPA JOINED Inheritance)
```
User (abstract base entity)
├── Customer  → CUSTOMER role
├── Trainer   → TRAINER role
└── Admin     → ADMIN role
```

### Core Relationships
- **Customer → Subscription** (1:N) — A customer can have multiple subscriptions over time
- **Subscription → Payment** (1:N) — Each subscription generates recurring payment records
- **Customer → PaymentMethod** (1:N) — Customers can register multiple payment methods (billing keys)
- **Subscription → SubscriptionPlan** (N:1) — Multiple subscriptions reference the same plan
- **Customer → FeedbackRequest** (1:N) — Customers submit trading feedback requests
- **FeedbackRequest → FeedbackResponse** (1:N) — Each request can receive multiple trainer responses
- **Customer → Memo** (1:1) — Each customer has exactly one memo
- **Lecture → Chapter** (1:N) — Lectures contain ordered chapters

### Additional Relationships
- **Customer → Consultation** (1:N) — Booking consultations
- **Customer → Review** (1:N) — Writing reviews
- **Customer → LevelTest** (1:N) — Taking level assessments
- **Customer → WeeklyTradingSummary** (1:N) — Weekly performance records
- **Customer → MonthlyTradingSummary** (1:N) — Monthly performance records
- **Customer → InvestmentTypeHistory** (1:N) — Investment type changes over time
