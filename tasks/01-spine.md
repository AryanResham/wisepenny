# Task 01 - Spine

Read `RULEBOOK.md` first. Then `IMPLEMENTATION.md` sections 4, 5, 6, 13 for reference shapes -
but the data model below is the source of truth where it differs.

## Goal

An Android project that compiles, installs, opens to three empty tabs, and has the full database
in place with default categories seeded. Nothing else. Every later task plugs into this.

No SMS reading. No Gemini. No pipeline. No real screens. Those are tasks 02-04.

## Environment

- The Android SDK is at the path in `local.properties` (`sdk.dir=...`). If that file is missing, look for
  the SDK at `%LOCALAPPDATA%\Android\Sdk` and create `local.properties` pointing at it.
- Build with `gradlew assembleDebug`. Test with `gradlew test`. Both must pass before you report.
- Use the Gradle wrapper. Do not assume a system Gradle.

## Files to create

### Build
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`
- `app/build.gradle.kts` - as IMPLEMENTATION.md section 4: namespace `com.aryan.expensetracker`,
  compileSdk = the highest platform installed under `<sdk>/platforms/` (check; do not assume 35),
  targetSdk same, minSdk 29, Compose + buildConfig on, KSP for Room, dependencies exactly as listed
  there (nothing extra). Load `secrets.properties` into `BuildConfig.GEMINI_API_KEY`; empty string if
  the file is missing. Configure Room schema export directory.
- `secrets.properties.example` - one line, `GEMINI_API_KEY=paste_here`.
- `.gitignore` - `secrets.properties`, `local.properties`, `build/`, `.gradle/`, `.idea/`, `*.iml`.

### Manifest
`app/src/main/AndroidManifest.xml`. Permissions: INTERNET, RECEIVE_SMS, READ_SMS,
ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION. Application class `.core.ExpenseTrackerApp`.
One activity, `.MainActivity`, launcher. No receivers or services yet - task 02 adds them.

### core/
- `ExpenseTrackerApp.kt` - Application. Builds `AppContainer`, seeds default categories on a background
  coroutine if the categories table is empty. That is all it does in this task.
- `AppContainer.kt` - builds once and exposes: `json` (kotlinx, ignoreUnknownKeys), the Room database,
  every DAO, an OkHttp client with the timeout from AppConfig, and `NetworkChecker`.
  Later tasks add their own fields here; leave a clearly commented spot for them.
- `config/AppConfig.kt` - every tunable. Include at least:
  BANK_SENDER_KEYWORD = "HDFC"; MONEY_WORDS list (debited, credited, sent, paid, spent, withdrawn, received);
  MAX_MESSAGE_LENGTH; DEDUP_LOOKBACK_MS (7 days); BANK_TIME_ZONE "Asia/Kolkata";
  GEMINI_MODEL, GEMINI_ENDPOINT, GEMINI_TIMEOUT_SECONDS; MAX_CORRECTION_EXAMPLES;
  MAX_PROCESSING_ATTEMPTS; WORKER_BACKOFF_SECONDS; MAX_LLM_CALLS_PER_DAY; BACKFILL_DAYS (90);
  MERCHANT_NOISE_WORDS (PVT, LTD, PRIVATE, LIMITED, INDIA, IN, POS, PAYMENT);
  DEFAULT_EXPENSE_CATEGORIES (Food, Travel, Groceries, Utilities, Housing, Entertainment, Investments, Misc);
  DEFAULT_INCOME_CATEGORIES (Income, Refund).
- `result/AppResult.kt` - the one result type for the project: sealed, `Success(data)` and
  `Failure(reason: String)`. Nothing else in it.
- `money/Money.kt` - two functions. `parseAmountToPaise(text)`: "1,234.50" -> 123450, returns null on
  garbage, uses BigDecimal not Double. `formatPaise(paise, direction)`: 45000 + DEBIT -> "-₹450.00".
- `net/NetworkChecker.kt` - `isOnline()`: true only when the active network is validated.
- `prefs/AppPrefs.kt` - thin wrapper over SharedPreferences. Keys: lastSeenSmsAt (Long),
  backfillDone (Boolean), llmCallsToday (Int) + llmCallsDate (String, yyyy-MM-dd). Getters and setters only.
- `nav/AppNavHost.kt` - bottom navigation with three destinations: Transactions, Review, Categories.
  Each destination is a placeholder composable showing its name. Routes as string constants.
- `ui/theme/` - minimal Material 3 theme. Do not spend time here.
- `MainActivity.kt` - sets content to the nav host. Nothing else.

### core/db/
- `AppDatabase.kt` - version 1, exportSchema true, `fallbackToDestructiveMigration()` in the builder
  (v1 accepts data loss on schema change; this is deliberate).
- `entity/TransactionEntity.kt` - table `transactions`. Columns:
  id (auto), amountPaise Long, direction String ("DEBIT"|"CREDIT"), merchantRaw, merchantNormalized,
  categoryId Long?, referenceNumber String?, accountTail String?, occurredAt Long, capturedAt Long,
  source String, needsReview Boolean, reviewReason String?, messageHash String, rawMessage String,
  latitude Double?, longitude Double?.
  Indexes: referenceNumber; messageHash unique.
- `entity/CategoryEntity.kt` - table `categories`. id, name (unique), kind ("EXPENSE"|"INCOME"), isDefault, sortOrder.
- `entity/MerchantRuleEntity.kt` - table `merchant_rules`. COMPOSITE primary key (normalizedMerchant, direction).
  Plus categoryId, source ("USER"), updatedAt. The direction in the key is deliberate: a Swiggy refund
  (CREDIT) must not inherit Swiggy's Food rule (DEBIT).
- `entity/PendingMessageEntity.kt` - table `pending_messages`. id, sender, body, receivedAt,
  attemptCount, messageHash (unique), latitude Double?, longitude Double?.
- `entity/ProcessedMessageEntity.kt` - table `processed_messages`. messageHash (primary key),
  outcome String ("SAVED"|"IGNORED"|"SUPERSEDED"), transactionId Long?, processedAt Long.
  Purpose: remember every message we have ever finished with, so a re-delivered SMS is never
  processed twice and never costs a second Gemini call.
- `entity/ReviewReason.kt` - string constants: NEW_MERCHANT, LLM_EXTRACTED, NO_REFERENCE_NUMBER, COULD_NOT_PROCESS.

### DAOs (each next to its owner, per RULEBOOK section 3)
- `feature/transactions/TransactionDao.kt` - observeAll (newest first), observeNeedingReview,
  findByReference(reference, direction, since) - direction is part of the match on purpose,
  insert, update, delete, reassignCategory(from, to).
- `feature/categories/CategoryDao.kt` - observeAll, getAll, findIdByName, count, insert, update, delete.
- `pipeline/categorization/MerchantRuleDao.kt` - findByMerchant(normalizedMerchant, direction),
  recentUserRules(limit), upsert, deleteByCategory.
- `pipeline/capture/PendingMessageDao.kt` - getAll (oldest first), insert with IGNORE on conflict,
  incrementAttempts(id), delete.
- `pipeline/capture/ProcessedMessageDao.kt` - exists(messageHash), insert with REPLACE.

## Tests
- `MoneyTest.kt` - parse: "1,234.50", "450", "1.00", "not a number" -> null. Format: DEBIT and CREDIT signs.
- Nothing else. The DAOs are exercised by later tasks.

## Out of scope
SMS, location reading, Gemini, WorkManager worker, pipeline, any real screen content, any onboarding.
If you find yourself writing any of these, stop.

## Done when
- [ ] `gradlew assembleDebug` succeeds from a clean checkout.
- [ ] `gradlew test` passes.
- [ ] App installs and shows three tabs that switch when tapped.
- [ ] Database Inspector shows five tables and the categories table has 10 seeded rows.
- [ ] `secrets.properties` missing -> app still builds and runs.
- [ ] Every file follows RULEBOOK sections 3-7. Comment count is low. No magic numbers outside AppConfig.
- [ ] Nothing outside the file list above was created.

## Report back
What you built, anything you deviated from and why, and the exact build/test commands you ran.
