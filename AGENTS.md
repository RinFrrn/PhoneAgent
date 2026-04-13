# AGENTS

## Purpose

This repository is evolving from a prompt-driven Android agent into a harness-driven runtime.

## Preferred Architecture

- `agent/PhoneAgent.kt` is a facade, not the main system of record.
- `harness/runtime` owns the task loop.
- `harness/observe` owns state collection.
- `harness/plan` owns model planning and action extraction.
- `harness/act` owns action execution and fallback.
- `harness/verify` owns post-action validation.
- `harness/trace` owns step and session persistence.
- `harness/eval` owns regression analysis.
- `harness/recover` owns failure taxonomy and future recovery routing.

## Rules

- Prefer typed fields over string parsing when adding new runtime behavior.
- Every new failure path should map to a `FailureType`.
- Every new verification rule should be visible in trace output.
- Add new execution heuristics through harness layers before editing `PhoneAgent`.
