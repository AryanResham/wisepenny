# RULEBOOK

Read this before touching anything. It applies to every agent, every model, every tool.
Nothing here depends on which AI harness you are running in.

Companion docs:
- `Design Doc.pdf`   - why the app exists. Background only.
- `IMPLEMENTATION.md` - original technical plan. Use it for data model, folder layout, and pipeline shape.
                        Where this file disagrees with it, THIS FILE WINS.
- `ideas.txt`         - parked ideas. Not in scope. Do not implement anything from it.
- `tasks/`            - one spec per task. You only work on the task you were given.

---

## 1. What we are building

An Android app (Kotlin, Jetpack Compose, Room) for one person. It reads HDFC bank SMS,
sends them to Gemini to extract amount / merchant / direction, categorizes them,
stores everything on the phone, and learns from the user's corrections.

## 2. v1 decisions (these override IMPLEMENTATION.md)

- Personal use, sideloaded. No Play Store rules apply. No per-brand battery guides.
- SMS is read DIRECTLY (RECEIVE_SMS + READ_SMS). Not via a notification listener.
  On first launch, backfill from the SMS inbox. On every launch, catch up from the last seen SMS.
- NO regex extraction templates. No rules.json. Gemini does all extraction.
  The only local filter is: sender contains "HDFC" AND body contains a money word.
- Last-known device location is SAVED with every transaction. It is NOT sent to Gemini in v1.
- Offline queue + WorkManager stays exactly as described in IMPLEMENTATION.md.
- Money is paise in a Long. Always.
- Gemini API key comes from a gitignored `secrets.properties` via BuildConfig.

## 3. Structure

Feature-first, single module, as in IMPLEMENTATION.md section 5, with these changes:
- `pipeline/capture/` holds the SMS receiver + inbox reader + filter + queue, not a notification listener.
- `pipeline/extraction/` has no RuleExtractor / RuleSet / RuleSetLoader. Only the Gemini extractor.
- Everything else stays.

Rules:
- One folder per feature or pipeline stage. Nothing else creates a folder.
- Max 2 levels of nesting below the package root.
- A file holds one main class plus its small private helpers. 20-200 lines. A 300-line file is
  fine if splitting it would make it harder to follow.
- Room entities live in `core/db/entity/`. DAOs live next to the feature that owns them.
- No repository layer. No use-case layer. Screens and pipeline talk to DAOs directly.
- All wiring happens in one place: `core/AppContainer.kt`. Plain constructors. No DI framework.

## 4. Code rules

SIMPLE BEATS CLEVER. A slightly slower app with obvious code is the goal.

- If a plain loop works, do not use recursion, nested lambdas, or a chain of operators.
- If a plain `if` works, do not build a sealed hierarchy or a strategy pattern.
- Guard clauses first. `if (bad) return` at the top. Never nested if/else towers.
- Functions are short (10-40 lines), do one thing, and are named with a verb: `readInbox`, `enqueueMessage`, `categorizeMerchant`.
- 1-3 parameters. If you need more, pass a small data class.
- Anything that can fail returns a result, not an exception. Use one shape across the project:
  a sealed `Result`-style type with Success(data) and Failure(reason). Callers check it and return early.
- Wrap I/O and network in try/catch at the boundary where it happens. Do not let exceptions travel upward.
- No frameworks beyond: Compose, Room, WorkManager, OkHttp, kotlinx-serialization. No Hilt, no Retrofit, no Koin.
- Every tunable number and list (keywords, timeouts, limits, model name) lives in `core/config/AppConfig.kt`.
  No magic numbers inline. Secrets never go in AppConfig.
- Enum-like columns are stored as String. No Room TypeConverters.
- Nothing may crash the app when a piece is missing: no API key, no internet, no location permission,
  empty inbox. Each of these degrades to "log it and carry on."

## 5. Comment rules

One short line above a block. Say WHAT it does, HOW, and WHY - in that order, only what is not obvious.

    // extract sms, run keyword filter and store in queue for categorization

- Roughly one comment per 20-30 lines. More is noise.
- Multi-step functions: number the steps. `// Step 1: ...`, `// Step 2: ...`
- Trailing inline comments only for a single non-obvious line. `// no-op if already closed`
- No file headers. No KDoc paragraphs. No @param / @return tags. No commented-out code. Ever.
- A comment that restates the code is deleted. `// increment counter` above `count++` is a violation.

## 6. Naming

- `camelCase` variables and functions, `PascalCase` classes, `SCREAMING_SNAKE` constants.
- Spell things out: `merchantName`, not `mName`. `transactionDao`, not `txDao`.
- Booleans read as questions: `isOnline`, `needsReview`, `hasReference`.
- Entities end in `Entity`, DAOs in `Dao`, screens in `Screen`, view models in `ViewModel`.

## 7. Logging

- Log with a per-file `TAG` constant.
- NEVER log an SMS body, a merchant name, an amount, a location, or an API key. Log lengths, ids, counts, and outcomes.
- Log every failure once, at the place it is caught. Do not log the same failure at three levels.

## 8. Tests

Keep them few and useful. They exist so a verifier can prove a task works without a phone.
- Unit tests only for: money parsing, the SMS filter, and the pipeline's decision logic (using in-memory fakes for DAOs, network, and Gemini).
- No UI tests. No instrumented tests.
- A test file mirrors the file it tests: `MessagePipeline.kt` -> `MessagePipelineTest.kt`.

## 9. Task protocol

Every task is a file in `tasks/`. It states: goal, files to create or touch, what each function does and why,
what is explicitly out of scope, and the "done when" checklist.

Implementer:
1. Read this file, then the task file, then only the parts of IMPLEMENTATION.md the task points to.
2. Touch only the files the task lists. If you must touch something else, stop and say so in your report.
3. Build must pass: `gradlew assembleDebug`. Tests must pass: `gradlew test`.
4. Finish with a short report: what was built, what was skipped and why, anything the verifier should look at.

Verifier (a fresh agent that did not write the code):
1. Run the build and the tests. If either fails, the task is not done. Report and stop.
2. Read every file the task listed against sections 3-7 of this file. Quote each violation with file and line.
3. Check the "done when" list item by item.
4. Report: PASS, or FAIL with a numbered list. No fixes - the verifier never edits code.

## 10. Forbidden

- Writing code outside the files your task lists.
- Implementing anything from `ideas.txt`.
- Regex extraction templates of any kind.
- Float or Double for money.
- Logging message contents.
- Adding a dependency not listed in section 4.
- A comment longer than one line, except numbered steps.
- Clever code where dumb code works.
