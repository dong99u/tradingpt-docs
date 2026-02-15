# CQRS (Command Query Responsibility Segregation) Pattern

## Overview

This project separates read and write operations into distinct service classes, following the CQRS principle. Each domain has a **Query Service** (reads) and a **Command Service** (writes) with different transactional behaviors.

## Why CQRS?

| Concern | Query Service | Command Service |
|---------|--------------|-----------------|
| Transaction | `@Transactional(readOnly = true)` | `@Transactional` |
| Flush Mode | Skip flush (performance gain) | Normal flush |
| DB Routing | Can route to read replica | Routes to primary |
| Dirty Checking | Disabled (no overhead) | Enabled for auto-persist |
| Responsibility | Read operations only | Create, Update, Delete |

## Benefits of `readOnly = true`

1. **No dirty checking overhead** - Hibernate skips snapshot comparison
2. **Flush skipped** - No need to synchronize persistence context
3. **Read replica routing** - DataSource routing can direct reads to slaves
4. **Clear intent** - Code explicitly communicates read-only semantics

## Structure

```
domain/{entity}/service/
├── query/
│   ├── {Entity}QueryService.java          (interface)
│   └── {Entity}QueryServiceImpl.java      (implementation)
└── command/
    ├── {Entity}CommandService.java         (interface)
    └── {Entity}CommandServiceImpl.java     (implementation)
```

## Key Files

| File | Description |
|------|-------------|
| `QueryService.java` | Read-only service with `@Transactional(readOnly = true)` at class level |
| `CommandService.java` | Write service with `@Transactional` at method level |
