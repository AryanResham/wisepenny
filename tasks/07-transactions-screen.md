# Task 07 - Transactions screen

Read `RULEBOOK.md` first. Depends on task 06 (regex extraction + new data model) being merged and green.

## Goal

One screen that shows the user their money and lets them tag it. This is the screen they open daily.

## Design direction - read this before writing any composable

A previous attempt was specced as "no design effort" and the result was rejected. Do not repeat that.
The bar is: **a person opening this for the first time understands what they are looking at without
being told, and it does not feel like a debug screen.**

Not a design system, not animations for their own sake. Five concrete rules:

1. **One thing dominates.** The month's total is the largest element on screen by a wide margin -
   display-size, bold, high contrast. Everything else is quieter than it. The user's first glance
   should land there with no effort.
2. **Hierarchy through weight and colour, not boxes.** Avoid stacking Cards inside Cards. Rows are
   separated by spacing and a hairline divider at most. The screen should read as one continuous
   list, not a pile of containers.
3. **Numbers line up.** Amounts are right-aligned with `FontFeature` tabular figures (or a monospace
   digit style) so the column scans vertically. A ragged amount column is the single biggest thing
   that makes a money app feel cheap.
4. **Colour carries exactly one meaning: direction.** Credits get the positive accent, debits stay
   the default text colour. Tags are neutral-toned chips. Do not colour-code tags - with 13 tags it
   becomes confetti and means nothing.
5. **Breathing room.** 16dp screen padding, 12-14dp vertical inside a row, 8dp between a payee and
   its description. Dense is fine; cramped is not.

Follow the device theme (`MaterialTheme` with dynamic colour where available, dark mode included).
Every string and every colour comes from the theme - no hardcoded hex, no hardcoded `Color.Gray`.

Touch targets are >= 48dp. Every icon-only button has a `contentDescription`.

Empty states are written, never blank: no transactions this month -> "Nothing here for October."
Search with no hits -> "No matches for 'swiggy'." First run with an empty database ->
"Waiting for your first message."

## Screen

### Header (always visible, does not scroll away)
- The month being viewed, with `<` `>` arrows to move between months. Defaults to the current month.
- That month's total spend, big. Plus income on a second line if there is any.
- A one-line health strip: `Last SMS: 2h ago` and, only when non-zero, `Unread: 3`
  (pending_messages count). This is the silent-failure alarm. Hide the "Unread" half when it is 0.

### Search
A text field under the header. Filters on payee AND description, case-insensitive, as you type.
When the box has text, the month filter is ignored and it searches everything.

### The list
Grouped by day with a small date header (`Today`, `Yesterday`, then `Mon 14 Oct`).
Each row:
- amount, right-aligned, monospace digits so columns line up. Debits plain, credits with a `+` and
  a distinct colour.
- payee on the left. Use `description` instead when one exists - it is more meaningful than
  `AARYAN JAIDEEP KANERIA`.
- a small tag chip when tagged. When untagged, nothing - no "Uncategorized" label, no warning
  colour, no badge. **Untagged is normal and must look normal.**

### Tagging
Two paths, both required:
- **Tap a row** -> bottom sheet with: the amount, payee, date, the original SMS text (small,
  selectable, collapsed to 4 lines with a "show more"), a tag picker (AppConfig.TAGS as chips),
  and a description field. Saving writes the transaction AND upserts a `PayeeRuleEntity` for that
  payee+tag so the next one is automatic.
- **Long-press a row** -> enters selection mode. Checkboxes appear, a bar at the bottom shows
  "3 selected" and a "Tag" button that opens the same tag picker and applies to all of them.
  Back or an X exits selection mode.

### Undo
After any tag write (single or bulk), show a snackbar "Tagged 3 as Food" with an UNDO action
lasting ~5 seconds. Undo restores the previous tag/description of each affected row and removes
any payee rule that write created. Keep the undo state in the ViewModel, not the database.

## Files

```
feature/transactions/
  TransactionsScreen.kt        the screen above
  TransactionsViewModel.kt     month window, search, selection, tagging, undo
  TransactionRow.kt            one row
  TagSheet.kt                  the bottom sheet (also used by bulk tagging)
feature/common/
  TagChip.kt                   one chip, used everywhere
  MonthHeader.kt               month switcher + totals + health strip
```

## DAO additions (TransactionDao)
```kotlin
fun observeBetween(from: Long, to: Long): Flow<List<TransactionEntity>>
fun observeSearch(query: String): Flow<List<TransactionEntity>>   // payeeRaw OR description LIKE
suspend fun setTag(ids: List<Long>, tag: String?, description: String?)
suspend fun byIds(ids: List<Long>): List<TransactionEntity>       // for undo
```
Totals are computed in the ViewModel from the observed rows - do not add SUM queries.

## Rules
- Money is formatted ONLY through `core/money/Money.formatPaise`. Never build a rupee string by hand.
- Dates through one helper in `feature/common/`. No date formatting inline in composables.
- No new dependencies. Stock Material 3 icons only.
- dp/sp values inline are fine. Everything else tunable goes in AppConfig.
- The ViewModel takes the DAOs it needs, built with `viewModelFactory` in the screen, as elsewhere.

## Out of scope
Chat (task 08), graphs, trends, settings, onboarding changes, the nav host beyond pointing at this
screen, export, location UI.

## Done when
- [ ] `gradlew.bat assembleDebug` green; `gradlew.bat test` still green
- [ ] Month switching, search, tap-to-tag, long-press bulk tag and undo all work
- [ ] Tagging a payee writes a payee rule; a later transaction from that payee arrives pre-tagged
- [ ] Untagged rows carry no badge, warning colour or count anywhere in the UI
- [ ] The original SMS is visible in the detail sheet
- [ ] No message body, payee, amount or location in any log line
- [ ] RULEBOOK sections 3-7 hold

## Report back
What you built, deviations, commands run, and anything you had to guess about the task 06 schema.
