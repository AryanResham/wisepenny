# Task 02 - SMS capture

Read `RULEBOOK.md` first. Depends on task 01 (spine) being merged and green.
Reference: `IMPLEMENTATION.md` section 9 "Capture" and section 11 - for the queue/worker shape only.
The notification-listener approach there is REPLACED by direct SMS reading.

## Goal

Every HDFC SMS - past and future - ends up in `pending_messages` exactly once, with the device's
last-known location attached, and the worker gets kicked. Junk SMS never enters the queue.

## Files to create

### pipeline/capture/
- `SmsReceiver.kt` - BroadcastReceiver for `SMS_RECEIVED`. Reassembles multipart messages into one
  body, takes sender and timestamp, hands them to `MessageCapture`. Uses `goAsync()` so the insert can
  finish after `onReceive` returns. Does no filtering itself.
- `InboxReader.kt` - reads `content://sms/inbox` newer than a given timestamp, oldest first, and hands
  each row to `MessageCapture`. Two callers: first-run backfill (last `BACKFILL_DAYS`) and catch-up on
  every app open (since `AppPrefs.lastSeenSmsAt`). Returns how many rows it looked at, as an AppResult.
- `BankMessageFilter.kt` - `looksLikeBankMessage(sender, body)`: sender contains
  `AppConfig.BANK_SENDER_KEYWORD` (case-insensitive) AND body contains any `MONEY_WORDS` entry AND body
  length under `MAX_MESSAGE_LENGTH`. Pure function, no Android imports, so it is unit-testable.
- `MessageHash.kt` - `hashMessage(sender, body)`: SHA-256 hex of `sender|body`.
- `MessageCapture.kt` - the ONE entry point both callers use. Steps:
  1. filter; drop if not a bank message
  2. hash; skip if `ProcessedMessageDao.exists(hash)` - already handled in a past run
  3. attach `LocationProvider.lastKnown()` (may be null)
  4. insert into `pending_messages` with IGNORE on conflict
  5. update `AppPrefs.lastSeenSmsAt` if this timestamp is newer
  6. `MessageProcessingScheduler.enqueue()`
  Returns an AppResult saying queued / skipped / failed. Never throws.

### core/location/
- `LocationProvider.kt` - `lastKnown()`: returns a small data class (lat, lng) or null. Uses
  `LocationManager.getLastKnownLocation` across GPS then network provider. Returns null when permission
  is missing. NO play-services dependency. Never requests a fresh fix.

### pipeline/
- `MessageProcessingScheduler.kt` - `enqueue(context)`: unique one-time work, policy KEEP (not APPEND -
  the worker drains the whole queue, so a second request is redundant), exponential backoff from
  AppConfig, NO network constraint.
- `MessageProcessingWorker.kt` - CoroutineWorker. In THIS task its body only reads the pending count,
  logs it, and returns success. Leave a one-line comment: `// task 05 replaces this body with the pipeline`.

### feature/onboarding/
- `OnboardingScreen.kt` + `OnboardingViewModel.kt` - shown when any required permission is missing.
  Requests RECEIVE_SMS, READ_SMS, and location (location is optional - the user may decline and the
  app still works). One button "Ignore battery optimization" that opens the standard Android
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` screen. One "Done" button that runs the backfill once
  (`AppPrefs.backfillDone`) and navigates to Transactions.
  Keep it one column of text and buttons. No design work.

### Edits to existing files
- `AndroidManifest.xml` - register `SmsReceiver` (exported, intent filter SMS_RECEIVED, priority high).
- `core/nav/AppNavHost.kt` - ONLY: make Onboarding the start destination when permissions are missing,
  otherwise Transactions. Do not touch the three placeholder screens - task 04 owns them.
- `core/AppContainer.kt` - add `bankMessageFilter`, `locationProvider`, `messageCapture`, `inboxReader`.
- `core/ExpenseTrackerApp.kt` - on create, after seeding: run `InboxReader` catch-up on a background
  coroutine, then `MessageProcessingScheduler.enqueue`.

## Tests
- `BankMessageFilterTest.kt` - HDFC debit passes; HDFC OTP (no money word) fails; non-HDFC sender with
  "debited" fails; over-length fails; case-insensitive sender.
- `MessageHashTest.kt` - same input same hash; different sender different hash.

## Out of scope
Gemini, categorization, the real worker body, real screens, any UI beyond the onboarding column.

## Done when
- [ ] Build and tests green.
- [ ] On the emulator, sending a fake SMS from sender "VM-HDFCBK" with body "Rs.450 debited from A/c
      XX1234 to SWIGGY" creates one row in `pending_messages`. Sending it again creates zero new rows.
- [ ] A fake SMS with body "Your OTP is 4821" creates nothing.
- [ ] Launching with an inbox that has HDFC messages fills the queue once; relaunching adds nothing.
- [ ] Logcat shows the worker ran and logged the pending count.
- [ ] No SMS body, sender, or location appears in any log line.
- [ ] RULEBOOK sections 3-7 hold in every new file.

## Report back
What you built, deviations, commands run, and one thing you think the verifier should look at hardest.
