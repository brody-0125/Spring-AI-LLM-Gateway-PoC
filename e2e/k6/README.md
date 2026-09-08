# k6 gateway scenarios

These scenarios exercise only the public gateway contract. They do not contain provider credentials.

Required environment variables:

- `K6_BASE_URL`: gateway URL, default `http://localhost:8080`
- `K6_GATEWAY_API_KEY`: client key used in `Authorization: Bearer ...`
- `K6_MODEL`: logical model group, default `default`

Scenario selection:

- `K6_SCENARIO=smoke` (default): JSON completion and request correlation
- `K6_SCENARIO=stream`: SSE content type and `[DONE]` termination
- `K6_SCENARIO=rate-limit`: accepts only the documented `200` or `429` boundary

Example:

```powershell
$env:K6_BASE_URL = "http://localhost:8080"
$env:K6_GATEWAY_API_KEY = "<gateway-client-key>"
$env:K6_MODEL = "default"
k6 run .\e2e\k6\gateway.js
```

Inject the key from a local ignored environment file, a CI secret, or a Kubernetes Secret. Do not put it in this directory or in shell history.
