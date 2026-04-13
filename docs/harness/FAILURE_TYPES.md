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
- `PERMISSION_MISSING`
- `USER_TAKEOVER_REQUIRED`
- `TASK_STOPPED`
- `MAX_STEPS_EXCEEDED`
- `UNKNOWN`

## Intent

- Model failures should be separated from execution failures.
- Verification failures should not be merged into generic action failures.
- Failure types should support reporting first, then recovery policy.
