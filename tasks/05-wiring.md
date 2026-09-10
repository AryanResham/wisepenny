# Task 05 - Wiring

Read `RULEBOOK.md` first. Depends on tasks 02, 03, 04 being merged into main and green.
Reference: `IMPLEMENTATION.md` section 9 "Orchestration" and section 11 - for shape only.
The decision rules below override it wherever they differ.

## Goal

Connect the pieces. A queued message goes in one end; a stored, categorized transaction comes out
the other, or a clear reason why not. After this task the app works end to end.

## Files to create

### pipeline/
- `PipelineOutcome.kt` - sealed: `Saved`, `Ignored(reason)`, `WaitForNetwork`, `Failed(reason)`.
  `WaitForNetwork` and `Failed` are DIFFERENT on purpose - see the worker rules.
- `MessagePipeline.kt` - `process(message: PendingMessageEntity): PipelineOutcome`. Steps, in order:
  1. already processed? `ProcessedMessageDao.exists(hash)` -> `Ignored("repost")`
  2. offline? -> `WaitForNetwork`. Nothing below runs without the network in v1.
  3. `LlmExtractor.extract` -> NotATransaction: record in processed_messages as IGNORED, return `Ignored`.
     Failed: return `Failed(reason)`. Transaction: continue with the draft.
  4. normalize the merchant with `MerchantNormalizer`.
  5. category: `LocalCategorizer` first. If found, reviewReason = LLM_EXTRACTED (every v1 extraction is
     LLM). If not found, `LlmCategorizer`; reviewReason = NEW_MERCHANT whether or not it returned an id.
     If the draft has no reference number and reviewReason is still LLM_EXTRACTED, use NO_REFERENCE_NUMBER
     instead. Priority is NEW_MERCHANT > LLM_EXTRACTED > NO_REFERENCE_NUMBER. One reason per row.
  6. dedup: `DuplicateChecker.check(reference, direction)`.
     - New: insert the row. needsReview = true always in v1 (every row is LLM-extracted).
     - Replaces(existing): update ONLY amountPaise, occurredAt, rawMessage, messageHash, accountTail on the
       existing row. Keep existing categoryId, needsReview, reviewReason, merchantNormalized, location.
       The user's earlier decision on that transaction is never undone by a later bank message.
       Record the OLD messageHash in processed_messages as SUPERSEDED so its re-delivery is ignored.
  7. record this message in processed_messages as SAVED with the transaction id. Return `Saved`.
  Every step that touches the network or the DB is wrapped so an exception becomes `Failed(reason)`,
  never a crash. Log outcomes and ids, never content.

### Edits to existing files
- `pipeline/MessageProcessingWorker.kt` - replace the placeholder body:
  - load the queue oldest first; for each message call the pipeline
  - `Saved` / `Ignored` -> delete from pending_messages
  - `WaitForNetwork` -> leave it, do NOT touch attemptCount, remember to return retry at the end
  - `Failed` -> incrementAttempts. If attemptCount is already at MAX_PROCESSING_ATTEMPTS, do not delete:
    insert a stub transaction with amountPaise 0, direction "DEBIT", merchantRaw "Could not process",
    needsReview true, reviewReason COULD_NOT_PROCESS, rawMessage = body, then delete from the queue.
    The user sees it in Review instead of it vanishing.
  - an exception from the pipeline counts as `Failed`
  - return `Result.retry()` if anything is still waiting, else `Result.success()`
- `core/AppContainer.kt` - add `messagePipeline`. If the Room database is still private, expose it -
  the pipeline may need `withTransaction` for the replace + processed_messages pair.
- `feature/review/ReviewScreen.kt` - add the plain-language line for COULD_NOT_PROCESS:
  "Could not be read after several tries". Nothing else.
- `pipeline/capture/InboxReader.kt` - `catchUp()` must return early (count 0) when `AppPrefs.backfillDone`
  is false, and its `since` must never be older than now - BACKFILL_DAYS. Today a fresh install scans the
  whole inbox from timestamp 0 before onboarding has run.

### Small fixes carried over from the task 02 verifier
- `SmsReceiver.kt` - the container lookup happens after `goAsync()` but outside the try/finally, so a
  throw there leaks the PendingResult. Move it inside so `finish()` runs on every path.
- `MessageCapture.kt` - advance `lastSeenSmsAt` for EVERY scanned SMS (filtered or not), not only the
  queued ones, so catch-up never rescans junk. And stop calling `enqueue()` per message: capture returns,
  the CALLER (receiver / inbox reader) calls `enqueue()` once after its batch.
- `AppNavHost.kt` - hide the bottom NavigationBar while the onboarding route is showing, so the user
  cannot skip past permission grant.

### Small fixes carried over from the task 03 verifier
- `LlmExtractor.kt` - validate the model's `direction` against the two allowed values; anything else
  is `Failed("bad direction")`. Today a stray lowercase "debit" would flow into every direction-keyed lookup.
- Move `ACCOUNT_TAIL_LENGTH` and the source constants (`"LLM"`, `"USER"`) into AppConfig; use them from
  LlmExtractor, the merchant-rule writers, and MerchantRuleDao's query.
- The extractor and categorizer return failure reasons without logging. The pipeline logs each once.

### Small cleanups carried over from the task 04 verifier
- `feature/common/` - move the duplicated helpers here and use them from both places:
  `categoriesForDirection(categories, direction)` (currently copied in TransactionsScreen and ReviewScreen),
  `rememberMerchant(merchantRuleDao, normalizedMerchant, direction, categoryId)` (copied in both view models),
  and the `"USER"` rule-source constant (move it to AppConfig as `RULE_SOURCE_USER`).
- `TransactionsViewModel` - `lastSeenSmsAt` is read once at construction and never updates. Re-read it
  whenever the screen comes to the foreground (a `refreshStatus()` the screen calls on resume is enough).
- `CategoriesViewModel.delete` - wrap reassign + rule delete + category delete in one Room transaction
  so a failure half-way cannot leave rules pointing at a dead category.

## Tests
- `MessagePipelineTest.kt` with in-memory fakes for every DAO, `NetworkChecker`, and `LlmClient`.
  One test per branch, minimum:
  - repost -> Ignored, no LLM call made
  - offline -> WaitForNetwork, no LLM call made
  - not a transaction -> Ignored, processed_messages has IGNORED
  - known merchant -> Saved with the learned category, LlmCategorizer NOT called
  - unknown merchant -> NEW_MERCHANT, LlmCategorizer called once
  - no reference number, known merchant -> NO_REFERENCE_NUMBER
  - same reference twice -> second call updates amount but keeps the first row's categoryId and needsReview
  - extractor Failed -> Failed, nothing written
- `MessageProcessingWorkerTest.kt` is NOT required - the worker is thin. Cover the attempt rules by
  reading, not testing.

## Merging (do this FIRST, before any of the above)
Branches task-02, task-03, task-04 each contain one commit on top of main. Merge them into main in
that order. Expected conflicts: `AppContainer.kt` (02 and 03 both add fields) and `AppNavHost.kt`
(02 changes start destination, 04 swaps placeholders). Resolve by keeping BOTH sides' additions.
Build and test after each merge. If a merge cannot build, stop and report which one and why.

## Out of scope
Anything not listed. No new screens, no regex, no location to Gemini, no export.

## Done when
- [ ] All three branches merged, `gradlew.bat assembleDebug` and `gradlew.bat test` green.
- [ ] MessagePipelineTest covers every branch above and passes.
- [ ] Reading the worker: offline never touches attemptCount; a message is never silently deleted.
- [ ] Reading the pipeline: on Replaces, categoryId / needsReview / reviewReason are never overwritten.
- [ ] No message body, merchant, amount, location, or key in any log line.
- [ ] RULEBOOK sections 3-7 hold in every new or edited file.

## Report back
Merge conflicts hit and how you resolved them, what was built, deviations, commands run, and what the
verifier should look at hardest.
