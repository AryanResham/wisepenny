# Task 06 - Core rework: regex extraction + new data model

Read `RULEBOOK.md` first. This task REPLACES much of tasks 02-05. The app is being rebuilt around a
different idea, proven against 3,248 of the user's real HDFC messages.

## Why this exists

Measured on the real corpus:
- 6 regex templates extract **94%** of real transactions with zero AI
- 918 distinct payee strings, but 187 repeat and cover **74% of the money**
- Brand aliases are the mess: SWIGGY appears 14 ways, ZERODHA 6, AMAZON 6
- Sub-brands are distinguishable in the text: `SWIGGY INSTAMART` != `SWIGGY LIMITED`

So: regex is the fast path, the LLM is the safety net, and nothing is ever silently dropped.

## Test corpus (IMPORTANT)

`app/src/test/resources/samples.json` holds the user's REAL bank messages.
- It is **gitignored. Never commit it, never print its contents in your report, never copy it elsewhere.**
- Shape: `[{"date": <epoch millis>, "addr": "...", "body": "..."}, ...]`
- Use it as the regression corpus. If it is missing, say so and write the unit tests against the
  inline examples in this spec instead.

## Delete

Remove these and their tests - the pipeline is being rewritten around extraction, not the LLM:
`pipeline/extraction/LlmExtractor.kt`, `pipeline/categorization/LlmCategorizer.kt`,
`pipeline/categorization/LocalCategorizer.kt`, `pipeline/dedup/*`, `pipeline/MessagePipeline.kt`,
`feature/review/*`, `feature/categories/*`, `core/db/entity/ReviewReason.kt`,
and the matching test files. Keep `core/llm/*` (the client is reused).

## Data model

Room version 2. `fallbackToDestructiveMigration(dropAllTables = true)` stays - v1 data is disposable.

```
TransactionEntity (table "transactions")
  id, amountPaise Long, direction String, payeeRaw String, payeeNormalized String,
  tag String?,                 -- fixed list, see AppConfig.TAGS. null = untagged
  description String?,         -- free text from chat or the user. searchable.
  occurredAt Long, capturedAt Long,
  referenceNumber String?, accountTail String?,
  source String,               -- "REGEX" | "LLM"
  templateId String?,          -- which regex matched; null when the LLM did it
  placeFromMessage String?,    -- ATM/card messages name the place in the text
  latitude Double?, longitude Double?,
  rawMessage String, messageHash String
  indices: referenceNumber; messageHash unique; occurredAt; payeeNormalized; tag

PayeeRuleEntity (table "payee_rules")
  payeeNormalized String  @PrimaryKey
  tag String
  updatedAt Long
  -- the learning table. one row per payee the user has tagged.

PendingMessageEntity, ProcessedMessageEntity - keep exactly as they are.
CategoryEntity, MerchantRuleEntity - DELETE.
```

Tags are a fixed list in AppConfig, not a table:
```kotlin
val TAGS: List<String> = listOf(
    "Food", "Groceries", "Travel", "Shopping", "Entertainment", "Utilities",
    "Health", "Investments", "Rent", "Transfers", "Daily spends", "Income", "Other",
)
```

## Extraction

### pipeline/extraction/RegexExtractor.kt

Templates live in `app/src/main/assets/templates.json`, loaded and compiled once. Same loader
pattern as the old RuleSetLoader: a bad file or a bad pattern logs and is skipped, never crashes.

Named groups the engine reads: `amount` (required), `payee` (required), `acct`, `date`, `ref`, `place`.

These eight templates are DERIVED FROM REAL MESSAGES and are known to work. Transcribe them
carefully - every one is load-bearing:

| id | direction | matches |
|---|---|---|
| `upi_sent` | DEBIT | `(Amt )?Sent Rs.X From HDFC Bank A/C Y To PAYEE On DATE Ref REF` |
| `upi_debit_vpa` | DEBIT | `HDFC Bank: Rs X debited from a/c Y on DATE to VPA PAYEE(UPI Ref No REF)` |
| `money_transfer` | DEBIT | `Money Transfer:Rs X from HDFC Bank A/c Y on DATE to PAYEE UPI: REF` |
| `upi_credit` | CREDIT | `HDFC Bank: Rs. X credited to a/c Y on DATE by a/c linked to VPA PAYEE (UPI Ref No REF)` |
| `credit_alert` | CREDIT | `Credit Alert! Rs.X credited to HDFC Bank A/c Y on DATE from VPA PAYEE (UPI REF)` |
| `upi_mandate` | DEBIT | `UPI Mandate: Sent Rs.X from HDFC Bank A/c Y To PAYEE DATE Ref REF` |
| `card_spent` | DEBIT | `Spent Rs.X From HDFC Bank Card Y At PAYEE On DATE` |
| `atm_withdrawal` | DEBIT | `You've withdrawn Rs.X On HDFC Bank Debit Card Y At PLACE` - payee is "ATM", `place` group fills `placeFromMessage` |

Sanity checks before accepting a match - a silent wrong value is worse than a miss:
- amount parses and is > 0
- payee is non-blank and <= 60 chars
- date, when present, parses; otherwise fall back to the message's arrival time
Fail any check -> treat the template as not matched and keep trying the others.

### Non-transactions

A message matching this is recorded as IGNORED and never reaches a template or the LLM:
`OTP`, `E-Mandate!`, `SmartPay Scheduled`, `will auto-debit`, `will be deducted`, `Forex Markup`,
`DECLINED`, `Declined!`, `Login Alert`, `PIN Change`, `Process Initiated`, `has requested Rs`,
`Upcoming mandate`, `Mandate Cancelled`, `successfully cancelled`, `successfully created`,
`registration on`, `statement`, `reward point`, `Dear Customer`, `will be charged`, `balance`,
`apply now`. Case-insensitive.

### LLM fallback

Only when no template matched AND the message is not a non-transaction. Reuse `GeminiClient`.
Returns the same draft shape; `source = "LLM"`, `templateId = null`.
Offline or rate-limited -> leave the message queued, do not count a failed attempt (as today).

Keep a count of fallbacks in AppPrefs (`llmFallbackCount`) so a sudden rise is visible - that is
the early warning that HDFC changed a format.

## Tagging

### pipeline/tagging/PayeeNormalizer.kt

Collapses a raw payee string to a stable key. Measured requirements:
- strip the `@provider` suffix of a VPA: `SWIGGY@YESPAY` -> `SWIGGY`
- uppercase, strip punctuation, collapse whitespace
- drop trailing corporate suffixes: `LIMITED, LTD, PRIVATE, PVT, INDIA, SERVICES, ENTERPRISES`
  so `SWIGGY LIMITED` and `SWIGGY LTD` both -> `SWIGGY`
- do NOT merge different sub-brands: `SWIGGYINSTAMART` must stay distinct from `SWIGGY`
- a numeric/opaque handle (`Q30103640`, `PAYTMQR2810...`) returns as-is, uppercased - it is still a
  stable key even though it is unreadable

### pipeline/tagging/AutoTagger.kt

In order:
1. `PayeeRuleDao.findByPayee(normalized)` -> that tag. This is the learned path and it is free.
2. Payee seen only once before AND amount below `AppConfig.DAILY_SPEND_MAX_PAISE` (default 30000 = Rs 300)
   -> tag `"Daily spends"`. Keeps tiny one-offs out of the way without ever asking.
3. Otherwise leave `tag = null`. **Untagged is a normal resting state. Nothing nags.**

No LLM here. Categorisation guessing is out of scope for this task.

## Pipeline

`pipeline/MessagePipeline.kt`, rewritten. `process(message)`:
1. already in processed_messages -> `Ignored("repost")`
2. matches the non-transaction filter -> record IGNORED, `Ignored("not a transaction")`
3. `RegexExtractor` -> draft, `source = REGEX`
4. no template matched -> offline? `WaitForNetwork`. rate-limited? `WaitForQuota`.
   otherwise LLM fallback; still nothing -> `Failed(reason)`
5. normalize payee, run `AutoTagger`
6. dedup on `referenceNumber` + direction within `DEDUP_LOOKBACK_MS`:
   - new -> insert
   - replaces -> update ONLY amountPaise, occurredAt, rawMessage, messageHash, accountTail.
     **Never overwrite tag or description.** Record the old hash as SUPERSEDED.
7. record SAVED with the transaction id, in the same DB transaction

Worker and drain rules are unchanged from task 05 - including: offline never costs an attempt,
a message out of attempts becomes a visible stub row rather than a deletion.

## Backfill

`InboxReader` keeps the existing shape but `AppConfig.BACKFILL_DAYS` becomes `3650` (all of it).
The corpus is 4.5 years and importing it all is free now that regex does the work.

## Tests

Against `samples.json`, with a fake `LlmClient` that must NEVER be called for a template match:
- `RegexExtractorTest` - **coverage assertion: >= 90% of non-filtered messages produce a draft.**
  Print the achieved percentage. Also assert specific known values:
  `Sent Rs.287.00 From HDFC Bank A/C *0904 To Amazon India On 12/05/25 Ref 131464924973`
  -> 28700 paise, DEBIT, "Amazon India", ref "131464924973"
- a promotional / OTP / DECLINED message produces no draft and is filtered as non-transaction
- `PayeeNormalizerTest` - the Swiggy family collapses to 2 keys (SWIGGY, SWIGGYINSTAMART);
  `9876543210@ybl` -> `9876543210`; `ZERODHA BROKING LIMITED` -> `ZERODHA BROKING`
- `AutoTaggerTest` - learned rule wins; a small one-off becomes Daily spends; otherwise null
- `MessagePipelineTest` - keep the existing branch coverage, updated to the new shapes.
  Must still pin: repost ignored, offline waits without burning an attempt, a replace never
  overwrites tag or description.

## Out of scope
Screens, chat, the agent, location capture UI, vendor merging, graphs. Tasks 07 and 08.

## Done when
- [ ] `gradlew.bat assembleDebug` and `gradlew.bat test` green
- [ ] RegexExtractorTest reports >= 90% coverage on the real corpus, and the number is in your report
- [ ] No LLM call happens for any message a template matched
- [ ] Deleted files are gone, nothing references them
- [ ] RULEBOOK sections 3-7 hold
- [ ] samples.json not committed, not printed, not copied

## Report back
The achieved regex coverage %, what you deleted, deviations, commands run, and what the verifier
should look at hardest. Do not paste message contents.
