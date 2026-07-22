# Failure Types

## Defined Types

- `OBSERVATION_FAILED`
- `MODEL_REQUEST_FAILED`
- `MODEL_BALANCE`
- `MODEL_AUTH`
- `ACTION_EXECUTION_FAILED`
- `ACTION_NOT_EFFECTIVE`
- `VERIFICATION_FAILED`
- `APP_NOT_FOUND`
- `APP_LAUNCH_BLOCKED`
- `APP_LAUNCH_CONFIRMATION_REQUIRED`
- `APP_LAUNCH_TARGET_NOT_REACHED`
- `SENSITIVE_CONFIRMATION_REQUIRED`
- `RECORDED_TARGET_MISSING`
- `RECORDED_STATE_TIMEOUT`
- `RECORDED_OBSTRUCTION_DETECTED`
- `PERMISSION_MISSING`
- `USER_TAKEOVER_REQUIRED`
- `USER_DENIED`
- `USER_INTERVENTION_TIMEOUT`
- `TASK_STOPPED`
- `RUNTIME_INTERRUPTED`
- `MAX_STEPS_EXCEEDED`
- `UNKNOWN`

## Intent

- Model failures should be separated from execution failures.
- Verification failures should not be merged into generic action failures.
- Sensitive confirmations must stop on explicit denial, blank confirmation, or timeout.
- Sessions left running by a process interruption must be closed as `RUNTIME_INTERRUPTED` during the next startup.
- Every runtime failure is routed through one of `RETRY`, `REPLAN`, `USER_INTERVENTION`, or `STOP`.
- Transient observation and model-request failures use bounded retries; permission, authentication, and balance failures stop immediately.
- Each recovery decision is persisted in step trace output with its route, failure type, attempt count, delay, and reason.
