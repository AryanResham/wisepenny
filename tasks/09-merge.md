# Task 09 - Merge and wire

Read `RULEBOOK.md` first. Depends on tasks 07 and 08 both being verified.

## Goal

One app. Both screens reachable, both working, nothing left on a branch.

## Merge first

Branches `task-07` (transactions screen) and `task-08` (chat) each hold one commit on top of main.
Merge in that order: `git merge --no-edit task-07`, build, test, then `git merge --no-edit task-08`,
build, test.

Known overlaps - resolve by keeping BOTH sides' additions:
- `core/AppContainer.kt` - each branch appends its own fields
- `core/nav/AppNavHost.kt` - 07 adds the transactions route, 08 adds the chat route and tab
- `core/config/AppConfig.kt` - 08 adds the agent constants
- `app/src/test/.../PipelineFakes.kt` - 08 adds one interface override

If a merge will not build, stop and report which one and why. Do not force it.

## Then fix the snapshot

Task 08 could not add queries to `TransactionDao` because task 07 owned that file, so its tools filter
an **in-memory snapshot of every transaction** on each tool call. With ~3,000 rows that is a full table
load per chat message. Now that both branches are merged, do it properly:

- Add to `TransactionDao`:
  ```kotlin
  @Query("SELECT * FROM transactions WHERE occurredAt BETWEEN :from AND :to "
       + "AND (:tag IS NULL OR tag = :tag) ORDER BY occurredAt DESC LIMIT :limit")
  suspend fun findForAgent(from: Long, to: Long, tag: String?, limit: Int): List<TransactionEntity>

  @Query("SELECT * FROM transactions WHERE (payeeRaw LIKE '%' || :q || '%' "
       + "OR description LIKE '%' || :q || '%') AND occurredAt BETWEEN :from AND :to "
       + "ORDER BY occurredAt DESC LIMIT :limit")
  suspend fun searchForAgent(q: String, from: Long, to: Long, limit: Int): List<TransactionEntity>
  ```
- Point `AgentTools.findTransactions` at these. Keep the `MAX_TOOL_ROWS` cap and keep the
  all-null-arguments refusal.
- The row-to-model mapping is unchanged and must stay exactly as narrow as it is: id, date, amount,
  payee, tag, description. Nothing else ever leaves the device.
- Its existing tests must still pass. Where a test asserted in-memory filtering, update the fake DAO,
  not the assertion's intent.

## Navigation

Two tabs, bottom bar: **Transactions** and **Chat**. Transactions is the start destination when
permissions are granted, onboarding when they are not (unchanged from the existing logic).

A tag written from chat must appear in the transactions list without a manual refresh - both read the
same Room `Flow`, so verify this holds rather than adding a refresh.

## Housekeeping

- Delete any screen, route or string left over from the deleted review/categories features.
- `AppNavHost` must have no dead routes.
- One pass over `AppContainer`: every field is used; nothing constructed twice.

## Tests
No new behaviour, so no new tests beyond keeping the merged suites green and updating the agent-tool
tests for the DAO change. Expect roughly 57+ tests after the merge.

## Out of scope
Refund linking, two-way transfer netting, graphs, trends, export. Those are task 10 if the user wants
them.

## Done when
- [ ] Both branches merged, `gradlew.bat assembleDebug` and `gradlew.bat test` green after each merge
- [ ] `AgentTools` queries the database; no full-table load per chat message
- [ ] Rows leaving the device still carry only id, date, amount, payee, tag, description
- [ ] Both tabs reachable; tagging in chat updates the list with no refresh
- [ ] No dead routes, no references to deleted features
- [ ] RULEBOOK sections 3-7 hold in everything you touched

## Report back
Merge conflicts hit and how resolved, the DAO change, deviations, commands run, and what the verifier
should look at hardest.
