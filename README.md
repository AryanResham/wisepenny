# Expense Tracker

> **UNDER DEVELOPMENT** — nothing here works yet.

An Android app that reads your bank SMS and turns them into a categorized spending log.
Everything stays on your phone.

## What it does

- Watches for HDFC bank SMS (UPI, card swipes).
- Sends the message to an AI model (Gemini, your own key) to pull out amount, merchant, direction.
- Files it under a category — food, travel, groceries, etc.
- Stores it all in a local database. No accounts, no server, no cloud.
- Learns: correct a category once and that merchant is filed the same way forever, without the AI.

## How it works

1. An SMS arrives. If it's from HDFC and mentions money, it goes into a queue.
2. A background job drains the queue: AI extracts the details, the app checks for duplicates
   and looks up whether it already knows the merchant.
3. Known merchant → categorized instantly, no AI call. Unknown → AI guesses, you confirm once.
4. Anything uncertain lands in a review list instead of being silently dropped.

## Interesting bits

- Only the merchant name leaves the phone for categorization — not the message, amount, or account.
- Money is stored as paise in integers. No floating-point rounding, ever.
- Last-known location is saved with each transaction for smarter categorization later.
- Queue-first design: if the phone kills the app mid-way, nothing is lost.

## Stack

Kotlin · Jetpack Compose · Room · WorkManager · OkHttp · Gemini API

## Docs

- `RULEBOOK.md` — coding rules for anyone (or any AI agent) touching this repo
- `IMPLEMENTATION.md` — the technical plan
- `tasks/` — build tasks, in order
- `ideas.txt` — parked ideas
