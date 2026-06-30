# Harness Overview

## Goal

Replace the opaque agent loop with a runtime that can be verified, traced, and evaluated.

## Current Flow

1. Collect observation
2. Run readiness diagnostics before task start
3. Build runtime diagnostic snapshots for the main UI
4. Attach device snapshot data to diagnostics
5. Build planning context
6. Preprocess first-step system commands into direct actions when confidence is high
7. Route sensitive first-step tasks through Ask_User confirmation before model planning
8. Create traceable TODO notes for obvious 3+ step tasks before model planning
9. Finish simple screen snapshot commands from the first observation without a model call
10. Inject matched skill guidance and prompt cards
11. Ask model for the next action when preprocessing does not apply
12. Apply optional humanized execution policy
13. Execute action with skill fallback
14. Verify post-action state
15. Apply app-aware verification rules and sensitive checkpoint detection
16. Record per-step timing breakdowns and slow-step runtime warnings
17. Attach runtime step-health warnings when tasks become long or near limits
18. Extract structured task notes from Note actions
19. Sanitize sensitive data in displayed and exported logs
20. Export sanitized task trace text from the trace detail screen
21. Search and filter task trace logs locally
22. Persist trace
23. Inspect trace storage size and surface storage warnings in diagnostics
24. Preview and clean old orphan trace files from the trace detail screen
25. Audit task history index health for missing traces and stale running entries
26. Delete finished task history entries with their trace files after confirmation
27. Summarize visual context usage from trace observations
28. Summarize recent model usage trends from trace model-call stats
29. Record model call statistics in planning traces
30. Analyze model call health from latency, usage, and context size
31. Summarize recent task performance from trace history
32. Analyze recent task health and dominant failure types
33. Support typed Ask_User interactions for runtime user collaboration
34. Support typed Answer actions for information-return tasks
35. Support typed clipboard read/write actions with trace visibility
36. Accept commercial standard-action aliases from the reference runtime
37. Map standard direction swipe and scroll actions to executable swipes
38. Estimate model-call cost from trace usage when pricing is known
39. Capture optional user answers from Ask_User/takeover overlay and feed them back into planning
40. Preserve failed terminal actions as failed task outcomes
41. Preserve user-intervention timeout state in planning memory
42. Map standard press_key recent/overview actions to the Android recent-apps view
43. Execute standard drag actions through accessibility gestures
44. Map supported standard key_event actions to Android accessibility global actions
45. Parse commercial model response wrappers before action extraction
46. Evaluate traces offline or through active eval

## Current Status

- Runtime abstraction: done
- Run readiness diagnostics: done
- Runtime diagnostic snapshot: done
- Runtime device snapshot in diagnostics: done
- Structured task notes from Note actions: done
- Safe log and diagnostic export sanitization: done
- Sanitized task trace export: done
- Task trace log search: done
- First-step task preprocessing: done
- Sensitive task confirmation preprocessing: done
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
- App-specific verifiers: done
- Sensitive checkpoint verification: done
- Step timing trace and slow-step warnings: done
- Optional humanized execution trace: done
- Humanized execution settings UI: done
- Bundled task shortcuts: done
- Bundled app launch aliases: done
- Generic verifier: done
- Trace persistence: done
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
