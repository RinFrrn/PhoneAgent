# Harness Overview

## Goal

Replace the opaque agent loop with a runtime that can be verified, traced, and evaluated.

## Current Flow

1. Collect observation
2. Build planning context
3. Ask model for the next action
4. Execute action with skill fallback
5. Verify post-action state
6. Persist trace
7. Evaluate traces offline or through active eval

## Current Status

- Runtime abstraction: done
- Generic verifier: done
- Trace persistence: done
- Offline eval: done
- Active eval runner: done
- App-specific verifiers: pending
- Structured recovery policy: pending
