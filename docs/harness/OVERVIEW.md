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
7. Inject matched skill guidance and prompt cards
8. Ask model for the next action when preprocessing does not apply
9. Apply optional humanized execution policy
10. Execute action with skill fallback
11. Verify post-action state
12. Attach runtime step-health warnings when tasks become long or near limits
13. Extract structured task notes from Note actions
14. Sanitize sensitive data in displayed and exported logs
15. Export sanitized task trace text from the trace detail screen
16. Search and filter task trace logs locally
17. Persist trace
18. Record model call statistics in planning traces
19. Summarize recent task performance from trace history
20. Analyze recent task health and dominant failure types
21. Evaluate traces offline or through active eval

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
- Recent task performance summary: done
- Recent task health analysis: done
- Runtime step-health warnings: done
- Optional humanized execution trace: done
- Humanized execution settings UI: done
- Bundled task shortcuts: done
- Bundled app launch aliases: done
- Generic verifier: done
- Trace persistence: done
- Offline eval: done
- Active eval runner: done
- App-specific verifiers: pending
- Structured recovery policy: pending
