# Distributed Scheduling with ShedLock

## Overview

This project uses **ShedLock** for distributed task scheduling, ensuring that scheduled tasks run **exactly once** across multiple application instances. Without distributed locking, each instance would execute the same scheduler independently, causing duplicate processing.

## Problem: Multi-Instance Scheduling

```
Instance A: @Scheduled(cron = "0 0 0 * * *") → Processes payments
Instance B: @Scheduled(cron = "0 0 0 * * *") → Processes payments (DUPLICATE!)
Instance C: @Scheduled(cron = "0 0 0 * * *") → Processes payments (DUPLICATE!)
```

## Solution: ShedLock

```
Instance A: Acquires lock → Processes payments ✓
Instance B: Lock exists → Skip ✗
Instance C: Lock exists → Skip ✗
```

## How It Works

1. **Lock acquisition**: Before execution, ShedLock tries to insert/update a lock record in the database
2. **Execution**: Only the instance that acquired the lock executes the task
3. **Lock release**: After execution (or after `lockAtMostFor` timeout), the lock is released
4. **Safety window**: `lockAtLeastFor` prevents re-execution within a minimum interval

## Configuration

```java
@Scheduled(cron = "0 0 0 * * *")  // Daily at midnight
@SchedulerLock(
    name = "recurringPaymentScheduler",     // Unique lock name
    lockAtMostFor = "PT24H",                // Max lock duration (safety)
    lockAtLeastFor = "PT23H"                // Min lock duration (prevents re-run)
)
```

| Parameter | Purpose | Example |
|-----------|---------|---------|
| `name` | Unique identifier for the lock | `"recurringPaymentScheduler"` |
| `lockAtMostFor` | Maximum lock hold time (safety against crashes) | `"PT24H"` (24 hours) |
| `lockAtLeastFor` | Minimum lock hold time (prevents duplicate runs) | `"PT23H"` (23 hours) |

## Stack

- **ShedLock**: 5.13.0
- **Provider**: JDBC (database-backed locks)
- **Database table**: `shedlock` (auto-created)

## Key Files

| File | Description |
|------|-------------|
| `RecurringPaymentScheduler.java` | Daily payment scheduler with ShedLock annotations |
