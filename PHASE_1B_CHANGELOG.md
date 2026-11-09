# Store Manager Pro — Phase 1B Changelog
## Targeted Defect Corrections & Engineering Changes (Final Gate & Audit Corrections)

This changelog records the specific changes made to the Phase 1B delivery up to the Final Gate closure, Final Audit Corrections, and Task 1 Desktop JVM Target Compatibility resolution.

---

### 0a. Phase 1B Task 1 — Desktop JVM Target Compatibility Resolution
- **Target Compatibility Fix (`apps/desktopApp/build.gradle.kts`)**:
  - Maintained JDK Toolchain 21 (`jvmToolchain(21)`).
  - Maintained Kotlin JVM Target at 11 (`jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)`).
  - Explicitly configured Java compilation compatibility targets (`sourceCompatibility` and `targetCompatibility`) to `JavaVersion.VERSION_11`.
  - Resolved `Inconsistent JVM-target compatibility detected for tasks 'compileJava' (21) and 'compileKotlin' (11)`.
  - Validated clean execution of `./gradlew :apps:desktopApp:compileJava :apps:desktopApp:compileKotlin :apps:desktopApp:build --no-build-cache --no-configuration-cache` (BUILD SUCCESSFUL).
  - Executed all 42 Desktop integration tests in `StoreDatabaseIntegrationTest.kt` with 100% pass rate (`tests="42" failures="0" errors="0"`).

---

### 0b. Phase 1B Final Audit Corrections (Deliverable v3)
- **Exact Existing Schema Validation (`DatabaseDriverFactory.desktop.kt`)**:
  - Replaced count-only checking with exhaustive structural schema verification:
    - Verifies all 55 table names.
    - Inspects all column definitions, constraints, and data types via `PRAGMA table_info`.
    - Normalizes and compares SQL definitions for all 107 explicit indexes.
    - Normalizes and compares SQL definitions for all 186 canonical triggers.
    - Calculates a SHA-256 fingerprint of all schema objects and validates it against the canonical baseline.
    - Added `test20b`: Proves that substituting or altering a trigger while preserving counts (186 triggers, 107 indexes, 55 tables) is detected and rejected without altering or deleting existing user data.
- **True Cross-Process Initialization Test (`test26`)**:
  - Implemented `CrossProcessInitRunner` to spawn an independent, second OS JVM process via `ProcessBuilder`.
  - Proved true OS-level file locking via `FileChannel.lock()` on `${dbName}.init.lock` across independent processes.
  - Proved only one process executes initialization, the second waits, both open the complete 55/107/186 database, and zero temporary staging files remain.
- **Target File Deletion Protection in Staging Cleanup (`DatabaseDriverFactory.desktop.kt`)**:
  - Removed logic that deleted `targetFile` based on `targetFile.length() == 0L`.
  - Updated `test27`: Proves pre-existing target files (both non-empty marker files and 0-byte existing files) are strictly protected against deletion or modification during collisions or failures.
- **Deterministic WAL Checkpoint Failure Testing (`test28a-e`)**:
  - Replaced pre-execution exception with injectable checkpoint seam (`checkpointResultOverride`).
  - Added dedicated test cases:
    - `test28a`: Checkpoint returns `busy != 0` -> aborts publication, preserves target.
    - `test28b`: Checkpoint returns `checkpointed < log` -> aborts publication, preserves target.
    - `test28c`: Successful checkpoint -> publishes clean database without sidecars.
    - `test28d`: Staging `-wal` sidecar remains non-empty -> aborts publication.
    - `test28e`: Staging cleanup after failure -> verifies deletion of `.staging.*` files.
- **StoreDatabaseManager Driver Cleanup Guard Test (`test30`)**:
  - Added injectable `databaseConstructor` parameter to `StoreDatabaseManager`.
  - Added `test30`: Forces database construction failure after driver creation, verifies `driver.close()` was executed, verifies `isOpen() == false`, and verifies subsequent clean initialization succeeds.
- **Microbenchmark & Report Metrics Realignment**:
  - Accurately renamed and documented benchmark as product/inventory write microbenchmark (Approach A).
  - Recorded actual SQLite runtime versions: Desktop JDBC SQLite 3.53.4, Python 3.40.1.
  - Aligned JUnit XML test counts: 37 tests (36 passed, 1 skipped, 0 failed).

---

### 1. Database Initialization Atomicity & Staging (Final Gate)
- **Updated `DatabaseDriverFactory.desktop.kt`**:
  - Removed any fallback mechanism using `REPLACE_EXISTING`.
  - Enforced strict `StandardCopyOption.ATOMIC_MOVE` publication.
  - Added exclusive cross-process lock via `FileChannel.lock()` on `${targetFile.name}.init.lock` alongside JVM mutex map.
  - Enforced checking target database existence *strictly after* acquiring the exclusive file lock.
  - Added collision detection: if the target database appears or exists, publication is immediately aborted with `DatabaseInitializationException`, preventing any overwrite.
  - Ensured that isolated staging files (`.staging.<UUID>`) are created strictly in the *same* parent directory as the target file to guarantee atomic filesystem rename.
  - Implemented safe staging rollback and cleanup on any error, deleting temporary staging files and leaving target untouched.

---

### 2. WAL Checkpoint Verification & Sidecar Cleanup (Final Gate)
- **Implemented `queryCheckpoint` in `DatabaseDriverFactory.desktop.kt`**:
  - Executes `PRAGMA wal_checkpoint(TRUNCATE);` and reads the returned 3-tuple `(busy, log, checkpointed)`.
  - Asserts `busy == 0` (no active readers/writers blocking checkpoint) and `checkpointed >= log` (all frames committed).
  - Explicitly closes staging driver prior to publication.
  - Verifies that `-wal` and `-shm` sidecars contain no uncheckpointed data (length == 0); non-empty sidecars trigger abort.
  - Added `test28_walCheckpointFailure_abortsPublicationAndCleansStaging` simulating checkpoint failure.

---

### 3. Durability Setting & Comparative Benchmark (Final Gate)
- **Corrected Durability Evaluation**:
  - Revised prior technical claim regarding `synchronous = NORMAL`; in WAL mode, `NORMAL` syncs only at checkpoint boundaries, leaving un-checkpointed commits susceptible to loss on power failure.
  - Adopted `PRAGMA synchronous = FULL` (value `2`) by default across connection properties, staging initialization, and active connections.
  - Updated `test21` and `test23` to assert `synchronous == 2L`.
  - Added `test29_comparativeBenchmark_normalVsFullDurability` executing 50 full real-world retail cycles (products, stock movements, quota issuance, draft sales, sale items, quota deductions, invoice completion, customer payments, and payment allocations).
  - **Empirical Results**:
    - `synchronous = NORMAL`: 138 ms
    - `synchronous = FULL`: 154 ms
    - Difference: +16 ms total (+0.32 ms per transaction; 1.12x ratio).

---

### 4. Existing Database Validation Decoupling
- **Decoupled Schema Verification**:
  - Existing databases are thoroughly validated (55 tables, 107 explicit indexes, 186 triggers, `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, `user_version = 1`) *before* executing `PRAGMA journal_mode = WAL`.
  - Rejection never alters or deletes existing file data.
  - Updated `test20` to perform independent raw reads asserting that customer data `TENANT_DO_NOT_DELETE` remains intact on disk.

---

### 5. Test Suite Enhancements & Closed Test Gaps
- **Updated `StoreDatabaseIntegrationTest.kt`**:
  - `test20`: Reopened rejected database with independent reader to assert exact customer record preserved.
  - `test23`: Validated all 4 connection parameters (`foreign_keys=1`, `busy_timeout=10000`, `journal_mode=wal`, `synchronous=2`).
  - `test26`: Verified concurrent initialization race protection via exclusive lock.
  - `test27`: Verified target database collision protection without overwrite.
  - `test28`: Verified WAL checkpoint failure abort and staging cleanup.
  - `test29`: Comparative durability benchmark (FULL vs NORMAL).
  - `test30`: Verified `StoreDatabaseManager` lifecycle and safe driver cleanup on initialization failure.
- **Updated `verify_canonical_schema.py`**:
  - Updated T45 Ed25519 smoke test to record `NOT RUN` when `cryptography` Python library is missing, preventing false passes.

---

### 6. Build System & Gradle Wrapper 9.7.0
- Updated `gradle-wrapper.properties` to Gradle 9.7.0.
- Executed and validated clean build via `./gradlew` on Linux.
- Provided `gradlew.bat` for Windows environments.
