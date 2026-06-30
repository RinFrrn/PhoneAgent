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
7. Finish simple screen snapshot commands from the first observation without a model call
8. Inject matched skill guidance and prompt cards
9. Ask model for the next action when preprocessing does not apply
10. Apply optional humanized execution policy
11. Execute action with skill fallback
12. Verify post-action state
13. Attach runtime step-health warnings when tasks become long or near limits
14. Extract structured task notes from Note actions
15. Sanitize sensitive data in displayed and exported logs
16. Export sanitized task trace text from the trace detail screen
17. Search and filter task trace logs locally
18. Persist trace
19. Inspect trace storage size and surface storage warnings in diagnostics
20. Preview and clean old orphan trace files from the trace detail screen
21. Audit task history index health for missing traces and stale running entries
22. Delete finished task history entries with their trace files after confirmation
23. Summarize visual context usage from trace observations
24. Summarize recent model usage trends from trace model-call stats
25. Record model call statistics in planning traces
26. Analyze model call health from latency, usage, and context size
27. Summarize recent task performance from trace history
28. Analyze recent task health and dominant failure types
29. Support typed Ask_User interactions for runtime user collaboration
30. Support typed Answer actions for information-return tasks
31. Support typed clipboard read/write actions with trace visibility
32. Accept commercial standard-action aliases from the reference runtime
33. Map standard direction swipe and scroll actions to executable swipes
34. Estimate model-call cost from trace usage when pricing is known
35. Capture optional user answers from Ask_User/takeover overlay and feed them back into planning
36. Preserve failed terminal actions as failed task outcomes
37. Preserve user-intervention timeout state in planning memory
38. Map standard press_key recent/overview actions to the Android recent-apps view
39. Execute standard drag actions through accessibility gestures
40. Map supported standard key_event actions to Android accessibility global actions
41. Parse commercial model response wrappers before action extraction
42. Evaluate traces offline or through active eval

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
- App-specific verifiers: pending
- Structured recovery policy: pending
