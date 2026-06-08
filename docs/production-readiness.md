# Production Readiness Checklist

> **Phase 0 Baseline Document.** Generated 2026-06-07.
> Tracks all requirements from the implementation specification (Section 24).
> Status: ❌ = not implemented, ⚠️ = partially/buggy, ✅ = verified correct.

## Native and ABI

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 1 | No allocator mismatches (C `free()` on GraalVM memory, or vice versa) | ❌ | 1 |
| 2 | All result strings freed exactly once (success and error paths) | ❌ | 1 |
| 3 | Error payloads freed (not discarded on `rc != 0`) | ❌ | 1 |
| 4 | ABI handshake (`struct_size`, version, capabilities) | ❌ | 9 |
| 5 | Incompatible native library rejected with clear error | ❌ | 9 |
| 6 | Thread-safe initialization (`pthread_once` / `InitOnceExecuteOnce`) | ❌ | 4 |

## Error Handling

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 7 | No `ERROR:` prefix in successful result payloads | ❌ | 2 |
| 8 | No silent empty result sets for internal errors | ❌ | 2 |
| 9 | No partial results returned as success on technical errors | ❌ | 7 |
| 10 | Original error cause visible in DuckDB error messages | ❌ | 1,2 |
| 11 | Error codes documented (status enum) | ❌ | 2 |

## Validation

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 12 | Validation profiles: full, structural, fast | ❌ | 6 |
| 13 | Default profile clearly defined | ❌ | 6 |
| 14 | Constraint validation tested | ❌ | 6 |
| 15 | AREA validation tested | ❌ | 6 |
| 16 | Correct message parser (CSV with quotes, commas, Unicode) | ❌ | 6 |
| 17 | Parallel validation tested | ❌ | 4,6 |

## XTF Reading

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 18 | Full qualified class name comparison (not `endsWith`) | ❌ | 7 |
| 19 | NULL vs empty string distinguished | ❌ | 7 |
| 20 | Corrupt file produces error, not partial result | ❌ | 7 |
| 21 | Geometry errors not silently swallowed | ❌ | 7 |
| 22 | Multiple baskets tested and correct | ⚠️ | 7 |

## Import

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 23 | No table name collisions across topics | ❌ | 10 |
| 24 | `mapping` parameter implemented or rejected with UNSUPPORTED | ❌ | 10 |
| 25 | Transaction wrapping (`BEGIN/COMMIT`) | ❌ | 10 |
| 26 | Import modes (create/replace/append) | ❌ | 10 |
| 27 | Consistent identifier quoting | ⚠️ | 10 |
| 28 | Type mapping documented | ⚠️ | 10 |

## Build

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 29 | Release build fails without native library | ❌ | 11 |
| 30 | No hardcoded local developer paths in release | ❌ | 11 |
| 31 | Clear version metadata (all 6 version components) | ⚠️ | 11 |
| 32 | Four target platforms (Linux x86_64, Linux ARM64, macOS ARM64, Windows x86_64) | ✅ | - |
| 33 | Artifact checksums | ❌ | 11 |
| 34 | Smoke tests run on all platforms in CI | ⚠️ | 12 |

## Quality

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 35 | AddressSanitizer (ASan) | ❌ | 12 |
| 36 | UndefinedBehaviorSanitizer (UBSan) | ❌ | 12 |
| 37 | LeakSanitizer (LSan) | ❌ | 12 |
| 38 | Parallel call tests | ❌ | 4,12 |
| 39 | Regression tests for all known bugs | ❌ | 0,12 |
| 40 | Documentation (installation, security, ABI, validation, errors, limitations, performance, troubleshooting) | ✅ | 0,13 |
| 41 | Known limitations documented | ✅ | 13 |

## Request Transfer

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 42 | No fixed request buffers for user-controlled input | ❌ | 3 |
| 43 | No silent truncation of user input | ❌ | 3 |
| 44 | Proper JSON builder on C side | ❌ | 3 |
| 45 | Proper JSON parser on Java side | ❌ | 3 |
| 46 | All tests platform-independent (paths with special chars, Unicode) | ❌ | 3 |

## Java Cache & Logger

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 47 | Thread-safe model cache (ConcurrentHashMap) | ✅ | 5 |
| 48 | Cache key includes file fingerprint | ✅ | 5 |
| 49 | Failed compilations not cached indefinitely as success | ✅ | 5 |
| 50 | No permanent global System.err redirection | ✅ | 5 |
| 51 | Logger thread-safe | ✅ | 5 |

## Native Library Extraction

| # | Requirement | Status | Phase |
|---|-------------|--------|-------|
| 52 | Atomic extraction (tmp + fsync + rename) | ❌ | 8 |
| 53 | Hash verification before use | ❌ | 8 |
| 54 | Parallel extraction safe | ❌ | 8 |
| 55 | Cache path includes version + hash | ❌ | 8 |

## Summary

| Category | Total | Done |
|----------|-------|------|
| Native and ABI | 6 | 0 |
| Error Handling | 5 | 0 |
| Validation | 6 | 0 |
| XTF Reading | 5 | 0 |
| Import | 6 | 0 |
| Build | 6 | 1 |
| Quality | 7 | 2 |
| Request Transfer | 5 | 0 |
| Java Cache & Logger | 5 | 5 |
| Native Library Extraction | 4 | 0 |
| **Total** | **55** | **8** |

---

## Measurement Methodology

Each requirement is verified by the corresponding Phase:

| Phase | Requirements Verified |
|-------|----------------------|
| 0 | Baseline documentation created |
| 1 | #1, #2, #3 |
| 2 | #7, #8, #10, #11 |
| 3 | #42-#46 |
| 4 | #6, #17 (partial), #38 |
| 5 | #47-#51 |
| 6 | #12-#17 |
| 7 | #9, #18-#22 |
| 8 | #52-#55 |
| 9 | #4, #5 |
| 10 | #23-#28 |
| 11 | #29-#33 |
| 12 | #34-#39 |
| 13 | #40, #41 |

**Current overall readiness:** 8/55 = 14.5%
