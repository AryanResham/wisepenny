# Task 04 - Screens

Read `RULEBOOK.md` first. Depends on task 01 (spine) being merged and green.
Reference: `IMPLEMENTATION.md` section 12 for intent; the code there is a sketch, not gospel.

## Goal

Replace the three placeholder tabs with working screens over the DAOs. Plain Material 3, no design
effort. Function over looks. Every screen reads from a Flow and writes through its ViewModel.

## Files to create

### feature/transactions/
- `TransactionsViewModel.kt` - exposes all transactions (newest first) and all categories as StateFlows.
  `changeCategory(transaction, categoryId)`: updates the row AND writes a USER merchant rule for
  (merchantNormalized, direction) - a correction is a correction wherever it happens.
- `TransactionsScreen.kt` - list grouped by day. Row: amount (via `formatPaise`), merchantRaw, category
  chip (or "Uncategorized"). Tap opens a bottom sheet with a category picker filtered to the
  transaction's kind (DEBIT -> EXPENSE, CREDIT -> INCOME).
  Top of screen, a one-line status strip: "Last bank SMS: <relative time>" from `AppPrefs.lastSeenSmsAt`
  and "Pending: <n>" from the pending_messages count. This is the silent-failure alarm - it must be
  visible without scrolling.

### feature/review/
- `ReviewViewModel.kt` - exposes items needing review and categories.
  `confirm(transaction)`: only valid when categoryId is not null; clears needsReview/reviewReason;
  writes the merchant rule.
  `confirmWithCategory(transaction, categoryId)`: sets the category, clears review, writes the rule.
  `discard(transaction)`: deletes the row.
- `ReviewScreen.kt` - one card per item: amount, merchantRaw, a plain-language reason line from
  reviewReason, a category picker filtered by kind, the rawMessage (small, max 4 lines), buttons
  "Looks right" (DISABLED when no category is set) and "Not a transaction".
  Empty state: "Nothing to review."

### feature/categories/
- `CategoriesViewModel.kt` - exposes categories. `add(name, kind)`, `rename(category, name)`,
  `delete(category, moveTransactionsTo)`: reassign transactions first, delete merchant rules for it,
  then delete the category. Refuse to delete the last category of a kind.
- `CategoriesScreen.kt` - two sections, Expense and Income. Add button opens a dialog with name + kind.
  Delete opens a dialog that REQUIRES picking the destination category before enabling Delete.

### Shared
- `feature/common/CategoryPicker.kt` - one composable used by all three screens. Takes a list, a
  selected id, an onPick. Nothing else.

### Edits to existing files
- `core/nav/AppNavHost.kt` - swap the three placeholders for the real screens. Do NOT touch the
  start-destination logic - task 02 owns that.
- `core/AppContainer.kt` - nothing. ViewModels are built in the screens with `viewModelFactory` and
  take DAOs from the container, as in IMPLEMENTATION.md section 13.

## Tests
None. Screens are checked by hand.

## Out of scope
Onboarding screen (task 02), any pipeline work, theming, animations, icons beyond stock Material.

## Done when
- [ ] Build green.
- [ ] Insert a few rows by hand (Database Inspector or a temporary debug seed you remove after):
      they show grouped by day, tapping one changes its category, and a merchant_rules row appears.
- [ ] A row with needsReview=1 shows on Review; "Looks right" is greyed until a category is picked;
      confirming removes it from Review and writes a merchant rule.
- [ ] Deleting a category with transactions forces a destination pick, and the transactions move.
- [ ] Status strip visible on Transactions without scrolling.
- [ ] RULEBOOK sections 3-7 hold in every new file.

## Report back
What you built, deviations, and anything you had to guess about the DAO shapes.
