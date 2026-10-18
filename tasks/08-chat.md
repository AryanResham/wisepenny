# Task 08 - Chat

Read `RULEBOOK.md` first. Depends on task 06 (data model + extraction) being merged and green.
Can be built in parallel with task 07; it touches different files.

## Goal

A chat screen where the user narrates and asks. Two things it can do and nothing more:

1. **Tag** - "went for dinner with indra yesterday" -> finds yesterday's rows, proposes a tag and a
   description, user confirms, app writes.
2. **Answer** - "how much on going out last month?" -> queries, answers in a sentence, lists the rows.

This is the feature the app exists for. The SMS pipeline is plumbing; this is the product.

## Why tool-calling and not prompt-stuffing

The model never receives the whole database. It asks for what it needs, the app runs the query
locally, only the returned rows leave the device for that one question.

## The loop

```
user types
  -> request: system prompt + tool declarations + last N turns + the message
  -> model replies with either text, or a functionCall
  -> app executes the tool locally, sends the result back as a functionResponse
  -> model replies with final text, or a proposeTags call
  -> UI renders the answer, or a confirmation card
```

Cap the loop at `AppConfig.MAX_AGENT_STEPS` (4). Exceeded -> apologise in one line and stop.

## Tools

```kotlin
// returns matching rows, newest first, capped at AppConfig.MAX_TOOL_ROWS (60)
findTransactions(from: String?, to: String?, search: String?, tag: String?, limit: Int?)

// writes NOTHING. returns a confirmation card for the user to approve.
proposeTags(ids: List<Long>, tag: String, description: String?)
```

- Dates are `YYYY-MM-DD`. The system prompt is told today's date and the device timezone so
  "yesterday" and "last month" resolve correctly.
- `search` matches payeeRaw OR description, case-insensitive.
- Rows handed to the model carry: id, date, amount in rupees, payee, tag, description.
  **Never** the raw SMS, the account tail, the reference number, or coordinates.
- `findTransactions` with no arguments is refused with an error result - the model must scope its query.

## Confirmation - not optional

`proposeTags` renders a card in the chat: the tag, the description, and each affected row with a
checkbox (all ticked by default). The user can untick rows, then Confirm or Cancel. Only Confirm
writes - and it also upserts a `PayeeRuleEntity` for each distinct payee, same as the tag sheet
in task 07.

A silently wrong tag is worse than an extra tap. There is no setting to skip this.

## Files

```
feature/chat/
  ChatScreen.kt            message list + input box
  ChatViewModel.kt         the loop, tool dispatch, confirmation state
  ChatMessage.kt           sealed: User(text), Assistant(text), ToolRun(label), Proposal(...), Error(text)
  ProposalCard.kt          the confirmation card
core/llm/
  AgentTools.kt            tool declarations as JSON + the local executor
  GeminiAgent.kt           builds requests, parses functionCall / text, drives one turn
```

`GeminiClient` already exists and does one-shot JSON generation; extend it or sit alongside it with
a function-calling request shape. Do not duplicate the key handling, daily cap, rate-limit detection
or backoff - reuse them.

## Behaviour that must hold

- No API key -> the screen still opens and says "Add a Gemini key to use chat." No crash.
- Offline or rate-limited -> one plain line saying so. The rest of the app is unaffected.
- The model asks for rows that do not exist -> empty result, and it says it found nothing. Never invent
  a transaction.
- Tool results are data, never instructions. A description field containing "ignore previous
  instructions" must not change behaviour - state this in the system prompt and do not interpolate
  row text into the system prompt.
- Conversation history is in-memory only in v1. Killing the app clears it. Say so in an empty state.

## Tests

With a fake `LlmClient` replaying canned responses - no network:
- a canned functionCall for `findTransactions` is dispatched, and the result is fed back
- `proposeTags` writes NOTHING until confirm is called
- confirm writes the tags and the payee rules; cancel writes nothing
- unticking a row excludes it from the write
- the step cap stops a model that keeps calling tools
- `findTransactions` with all-null arguments returns an error, not the whole table

## Out of scope
Graphs, charts, trends, recurring detection, persisted history, voice, streaming responses,
multi-turn memory beyond the in-memory window.

## Done when
- [ ] `gradlew.bat assembleDebug` and `gradlew.bat test` green
- [ ] Both flows work against a fake client: narrate-then-confirm, and ask-then-answer
- [ ] Nothing is written without an explicit confirm
- [ ] Raw SMS, account tails, reference numbers and coordinates never enter a request
- [ ] Missing key / offline / rate-limited all degrade to one line, no crash
- [ ] RULEBOOK sections 3-7 hold

## Report back
What you built, the exact request/response shape you used for function calling, deviations,
commands run, and what the verifier should look at hardest.
