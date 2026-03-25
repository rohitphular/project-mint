# Quick Reference

**Date:** Thursday, March 26, 2026
**Format:** Pair programming, 90 minutes, bring own laptop
**Project:** Supermarket transaction processing (Java 21, Spring Boot 3.2, PostgreSQL, Kafka)

---

## Files

### [bugs.md](bugs.md)
Two deliberate bugs in the stats endpoint, exact line numbers, fixes, arithmetic proof, scripted narration for the debugging walkthrough, and the JUnit test to write after. **Target: 7-10 min, max 18 min.**

### [domain-objects.md](domain-objects.md)
Existing DTO inventory, 6 new response records to create (with full code), controller before/after changes, endpoint-to-DTO quick reference table, till simulator validation constraints, actuator vs custom health analysis, money storage discussion, RoundingMode deep dive, timestamp format gotcha.

### [kafka-consumer.md](kafka-consumer.md)
The main task: building the missing Kafka consumer. Step-by-step from dependency to config to implementation. Data mapping gotchas table, Docker networking, idempotency, error handling, DLQ discussion, consumer group semantics, testing approach, engineering conversation topics (scalability, resilience, consistency, observability, REST-to-Kafka migration). **Target: 40-50 min.**

---

## Time Budget (90-minute session)

| Time | Phase | File |
|------|-------|------|
| 0-5 min | Setup / health check | -- |
| 5-20 min | Bug investigation + fix | [bugs.md](bugs.md) |
| 20-25 min | Design discussion (business impact, rounding) | [bugs.md](bugs.md) |
| 25-65 min | Kafka consumer build | [kafka-consumer.md](kafka-consumer.md) |
| 65-80 min | Enhancement (error handling, metrics, tests) | [kafka-consumer.md](kafka-consumer.md) |
| 80-90 min | Architecture discussion | [kafka-consumer.md](kafka-consumer.md) |
| If time permits | DTO refactoring | [domain-objects.md](domain-objects.md) |

---

## Quick Reference Card

```
BUGS:
  1. TransactionController.java:409  ->  BigDecimal.ONE should be BigDecimal.ZERO
  2. Calculator.java:9               ->  ++count should be count
                                         Rename to calculateAverage
                                         RoundingMode.DOWN -> HALF_EVEN

CORRECT STATS (STORE-001, 3 seed txns of 7.69):
  totalTransactions: 3, totalAmount: 23.07, averageAmount: 7.69

BUGGY STATS:
  totalTransactions: 3, totalAmount: 24.07, averageAmount: 6.01

KAFKA CONSUMER (3 steps):
  1. build.gradle: add spring-boot-starter-kafka
  2. application.yml: add spring.kafka.* config
  3. New class: TransactionKafkaConsumer with @KafkaListener

KEY URLs:
  App:       http://localhost:8080/api/transactions/health
  Stats:     http://localhost:8080/api/transactions/stats/STORE-001
  Kafka UI:  http://localhost:8081
  Actuator:  http://localhost:8080/actuator/health
  Grafana:   http://localhost:3000 (admin/admin)
  Prometheus: http://localhost:9090
```
