# Harness Overview

## Goal

Replace the opaque agent loop with a runtime that can be verified, traced, and evaluated.

## Current Flow

1. Collect observation
2. Run readiness diagnostics before task start
3. Build runtime diagnostic snapshots for the main UI
4. Attach device snapshot data to diagnostics
5. Warn when the selected model appears mismatched with vision/hybrid mode
6. Build planning context
7. Preprocess first-step system commands into direct actions when confidence is high
8. Route sensitive first-step tasks through Ask_User confirmation before model planning
9. Create traceable TODO notes for obvious 3+ step tasks before model planning
10. Finish simple screen snapshot commands from the first observation without a model call
11. Inject matched skill guidance and prompt cards
12. Ask model for the next action when preprocessing does not apply
13. Apply optional humanized execution policy
14. Execute action with skill fallback
15. Verify post-action state
16. Apply app-aware verification rules and sensitive checkpoint detection
17. Require a final observation before accepting explicit Finish actions
18. Record per-step timing breakdowns and slow-step runtime warnings
19. Attach runtime step-health warnings when tasks become long, near limits, or near estimated context budget
20. Extract structured task notes from Note actions
21. Sanitize sensitive data in displayed and exported logs
22. Export sanitized task trace text from the trace detail screen
23. Search and filter task trace logs locally
24. Persist a minimized session snapshot at session start and after every step using atomic file replacement
25. Inspect trace storage size and surface storage warnings in diagnostics
26. Preview and clean old orphan trace files from the trace detail screen
27. Audit task history index health for missing traces and stale running entries
28. Delete finished task history entries with their trace files after confirmation
29. Summarize visual context usage from trace observations
30. Summarize recent model usage trends from trace model-call stats
31. Record model call statistics in planning traces
32. Analyze model call health from latency, usage, and context size
33. Summarize recent task performance from trace history
34. Analyze recent task health and dominant failure types
35. Support typed Ask_User interactions for runtime user collaboration
36. Support typed Answer actions for information-return tasks
37. Support typed clipboard read/write actions with trace visibility
38. Accept commercial standard-action aliases from the reference runtime
39. Map standard direction swipe and scroll actions to executable swipes
40. Estimate model-call cost from trace usage when pricing is known
41. Capture optional user answers from Ask_User/takeover overlay and feed them back into planning
42. Preserve failed terminal actions as failed task outcomes
43. Preserve user-intervention timeout state in planning memory
44. Map standard press_key recent/overview actions to the Android recent-apps view
45. Execute standard drag actions through accessibility gestures
46. Map supported standard key_event actions to Android accessibility global actions
47. Parse commercial model response wrappers before action extraction
48. Evaluate traces offline or through active eval

## Current Status

- Runtime abstraction: done
- Run readiness diagnostics: done
- Runtime diagnostic snapshot: done
- Runtime device snapshot in diagnostics: done
- Model/mode fit readiness warning: done
- Structured task notes from Note actions: done
- Safe log and diagnostic export sanitization: done
- Sanitized task trace export: done
- Task trace log search: done
- First-step task preprocessing: done
- Sensitive task confirmation preprocessing: done
- Sensitive confirmation denial and timeout termination: done
- Complex task TODO preprocessing: done
- Prompt card guidance injection: done
- Model call statistics in trace: done
- Session-level model statistics summary: done
- Model call health analysis: done
- Recent task performance summary: done
- Recent task health analysis: done
- Typed Ask_User interaction requests: done
- Typed Answer result actions: done
- Typed clipboard read/write actions: done
- Commercial standard-action aliases: done
- Standard direction swipe/scroll mapping: done
- Standard recent-apps press_key mapping: done
- Standard drag action execution: done
- Standard key_event global-action mapping: done
- Robust wrapped model response parsing: done
- Model cost estimation in trace summaries: done
- User answer handoff from overlay: done
- Failed terminal action outcomes: done
- User-intervention timeout memory: done
- Runtime step-health warnings: done
- Runtime context budget warnings: done
- App-specific verifiers: done
- Sensitive checkpoint verification: done
- Final observation verification for explicit Finish actions: done
- Step timing trace and slow-step warnings: done
- Optional humanized execution trace: done
- Humanized execution settings UI: done
- Bundled task shortcuts: done
- Bundled app launch aliases: done
- Generic verifier: done
- Trace persistence: done
- Crash-resilient per-step atomic trace persistence: done
- At-rest trace image removal and sensitive field minimization: done
- Background startup recovery for interrupted sessions and one-time legacy trace minimization migration: done
- User-confirmed interrupted-task continuation via a new linked session and mandatory fresh observation: done
- Trace storage inspection in diagnostics: done
- Old orphan trace cleanup: done
- Task history index health: done
- Finished task history deletion: done
- First-step screen snapshot preprocessing: done
- Visual context summary: done
- Recent model usage trend: done
- Offline eval: done
- Active eval runner: done
- Structured recovery policy: pending
