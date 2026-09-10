# Task 03 - Gemini extraction and categorization

Read `RULEBOOK.md` first. Depends on task 01 (spine) being merged and green.
Reference: `IMPLEMENTATION.md` sections 9 (Extraction - Gemini fallback, Categorization, Dedup) and 10.
Everything about regex/RuleExtractor/RuleSet there is DELETED from scope.

## Goal

Given an SMS body, get back a structured transaction (or "not a transaction") from Gemini.
Given a merchant, get back a category - from what the user taught first, from Gemini only if unknown.
No pipeline orchestration yet - that is task 05. Every class here is a pure building block.

## Files to create

### core/llm/
- `LlmClient.kt` - an interface with one function: `generateJson(systemInstruction, userText, responseSchema)`
  returning `AppResult<String>`. Exists so tests can fake it.
- `GeminiClient.kt` - implements `LlmClient` with OkHttp. Steps:
  1. no API key -> Failure, logged once
  2. daily cap: if `AppPrefs.llmCallsToday >= MAX_LLM_CALLS_PER_DAY` for today's date -> Failure("daily cap")
  3. POST to `GEMINI_ENDPOINT` formatted with `GEMINI_MODEL`, key in `x-goog-api-key` header, temperature 0,
     `responseMimeType` json, `responseSchema` inline
  4. increment the daily counter on any completed HTTP call
  5. dig `candidates[0].content.parts[0].text` out of the envelope; anything unexpected -> Failure
  Log HTTP codes and failure reasons. Never log `userText` or the key.
- `GeminiSchemas.kt` - two JSON schema strings, EXTRACTION and CATEGORIZATION, as in IMPLEMENTATION.md
  section 10.

### pipeline/extraction/
- `TransactionDraft.kt` - data class: amountPaise, direction, merchantRaw, referenceNumber?, accountTail?,
  occurredAt, source.
- `ExtractionOutcome.kt` - sealed: `Transaction(draft)`, `NotATransaction`, `Failed(reason)`.
- `LlmExtractor.kt` - `extract(body, receivedAt)`: builds the prompt, calls `LlmClient`, parses the JSON,
  converts to a draft using `parseAmountToPaise` and the bank time zone for dates.
  System prompt rules the model must follow:
  - isTransaction false for OTPs, balance alerts, offers, reminders, "cashback on your next order"
  - amount as plain decimal string, no symbol, no commas
  - merchant is the counterparty EXACTLY as written in the message, no cleanup. (Cleanup happens
    locally in MerchantNormalizer so the rule path and the AI path produce the same key.)
  - occurredOn as YYYY-MM-DD or null
  Missing amount/direction/merchant on a "true" result -> Failed.

### pipeline/categorization/
- `MerchantNormalizer.kt` - `normalize(raw)`: strip the `@provider` part of a UPI id, uppercase,
  replace non-letters with spaces, drop `AppConfig.MERCHANT_NOISE_WORDS`, collapse spaces. If nothing
  is left (numeric UPI id), return the uppercased handle before the @. Pure function.
- `LocalCategorizer.kt` - `categoryIdFor(normalizedMerchant, direction)`: look up `MerchantRuleDao`
  with BOTH merchant and direction. Returns Long? . Nothing else - no bundled merchant list in v1.
- `LlmCategorizer.kt` - `categoryIdFor(normalizedMerchant, direction)`: loads categories of the matching
  kind (CREDIT -> INCOME, DEBIT -> EXPENSE), builds a prompt with the category names and the last
  `MAX_CORRECTION_EXAMPLES` user rules as "merchant -> category" lines, asks for exactly one name or null,
  maps the name back to an id case-insensitively. Returns Long?. Only the merchant name and direction
  leave the phone - never the message body, amount, or location.

### pipeline/dedup/
- `DuplicateDecision.kt` - sealed: `New`, `Replaces(existing)`.
- `DuplicateChecker.kt` - `check(referenceNumber, direction)`: blank reference -> New. Otherwise
  `TransactionDao.findByReference(reference, direction, since = now - DEDUP_LOOKBACK_MS)`; found -> Replaces.

### Edits to existing files
- `core/AppContainer.kt` - add `llmClient`, `llmExtractor`, `merchantNormalizer`, `localCategorizer`,
  `llmCategorizer`, `duplicateChecker`.

## Tests (all with a fake `LlmClient` returning canned JSON - no network)
- `MerchantNormalizerTest.kt` - "swiggy@okhdfcbank" -> "SWIGGY"; "SWIGGY BUNDL TECHNOLOGIES PVT LTD" ->
  "SWIGGY BUNDL TECHNOLOGIES"; "9876543210@ybl" -> "9876543210"; extra spaces collapse.
- `LlmExtractorTest.kt` - canned transaction JSON -> correct draft in paise; canned isTransaction=false ->
  NotATransaction; malformed JSON -> Failed; missing amount -> Failed.
- `LlmCategorizerTest.kt` - with in-memory fake DAOs: canned "Food" -> Food's id; canned "Nonsense" -> null;
  canned null -> null; CREDIT only sees INCOME categories.

## Out of scope
The pipeline that strings these together, the worker, storing anything, any UI.

## Done when
- [ ] Build and tests green.
- [ ] With a real key in `secrets.properties`, a tiny debug log line on app start proves one real
      extraction round-trip works (remove the debug call before reporting, or gate it behind a
      BuildConfig.DEBUG check and say so).
- [ ] The daily cap actually blocks the call when the counter is at the limit.
- [ ] No prompt text, message body, merchant, or key in any log line.
- [ ] RULEBOOK sections 3-7 hold in every new file.

## Report back
What you built, deviations, commands run, and what the verifier should look at hardest.
