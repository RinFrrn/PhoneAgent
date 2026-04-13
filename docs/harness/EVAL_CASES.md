# Eval Cases

## Purpose

Eval cases define regression expectations over session traces and can also define executable active eval tasks.

## Active Eval Minimum Fields

- `id`
- `name`
- `taskGoal`

## Useful Assertions

- expected success or failure
- maximum allowed steps
- expected keywords in outcome message
- minimum verification pass rate

## Guidance

- One user goal per eval case
- Keep assertions narrow and testable
- Use trace evidence to justify new eval cases
