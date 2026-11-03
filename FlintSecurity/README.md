# Flint Security

Flint Security intercepts HTTP requests before static file serving, redirects, or upstream proxying. It is enabled by default. Visitors see a white page with a red STOP banner, the Flint Security heading, the connection-verification message, and an accessible checkbox. Clicking the box runs a fresh challenge and submits it for server validation. Successful verification reloads the originally requested page with a signed clearance cookie.

## Running

Run `npm start` from the repository root. No external API, package, or account is required for the default proof-of-work mode. Use HTTPS in production; the browser's cryptographic worker requires a secure context (localhost also works).

Configure the `security` object in the existing `FLINTWS_CONFIG` JSON file:

```json
{
  "security": {
    "difficulty": 16,
    "challengeSeconds": 120,
    "clearanceSeconds": 1800,
    "maxClients": 10000,
    "secureCookies": true
  }
}
```

Set `secureCookies` to true when HTTPS terminates at an ingress proxy. This sets the Secure cookie flag and requires an HTTPS Origin on verification submissions. Direct HTTP localhost development uses the default false. Disable the gate for a deliberately public service with `"security": {"enabled": false}`.

Signing keys are generated with cryptographic randomness at process startup. To retain valid clearance across restarts, set `signingKeyEnv` to the name of an injected environment variable containing at least 32 bytes of cryptographically random key material. Secret values never appear in page data or logs. Pending challenges and abuse counters remain process-local; use sticky sessions when running several replicas.

## Verification protocol

- A cryptographically random challenge ID, visitor binding, difficulty, and expiry are signed with HMAC-SHA256. Challenges expire after two minutes by default.
- The browser uses a Web Worker to find a SHA-256 nonce satisfying the issued difficulty. The server independently checks the proof. The checkbox alone never grants clearance.
- A challenge is consumed before asynchronous provider validation. Reuse, tampering, expired challenges, cross-origin submissions, and changes to the connection binding are rejected.
- Clearance is signed, time-limited, host-bound, and bound to the socket IP and User-Agent. The cookie is host-only, HttpOnly, and SameSite=Strict. Verification cookies are removed from proxied upstream requests.
- Challenge difficulty increases with repeated requests. Verification attempts are capped at 60 per source IP per minute. Challenge and client maps are bounded and expired entries are removed.
- API requests without clearance receive JSON 403 responses, rather than an HTML page. Requests are not replayed automatically. Browser navigations requesting HTML receive the challenge page. The exact `/healthz` path stays available for readiness probes and always uses the server's own health handler. Gateway authentication and configured global rate limits still apply.
- Verification bodies are limited to 8 KiB. The challenge page uses a restrictive Content Security Policy and cannot be framed or cached. Protected content never appears in the interstitial response.

Proof-of-work imposes a verifiable computation cost on automation. It does not classify a visitor as human: automated browsers can solve it too. Use the Turnstile mode below when managed bot detection is required.

## Optional Cloudflare Turnstile

Configure a real Turnstile widget for the site's hostname in Cloudflare, then inject its real site key and secret as environment variables. Set `turnstileSiteKeyEnv` and `turnstileSecretEnv` to those variable names inside `security`. Both values must be populated; startup fails if one is missing. No demo keys or unconditional-success fallbacks are included.

When enabled, the checkbox loads the real Turnstile widget. The server posts its token to `https://challenges.cloudflare.com/turnstile/v0/siteverify` and requires success, the current hostname, action `flint_security`, and the challenge ID in cdata. Network errors fail closed. Tokens are never accepted using a client-only check. The Cloudflare destination must be allowed by the deployment's outbound network policy; this mode has not been validated against a live account in this workspace.

## Research

Implementation was informed by Cloudflare's official documentation source, read from its GitHub repository:

- [Server-side validation](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/docs/turnstile/get-started/server-side-validation.mdx): mandatory Siteverify checks, single-use tokens, five-minute token expiry, hostname and action validation.
- [Clearance cookies](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/docs/cloudflare-challenges/concepts/clearance.mdx): visitor-bound clearance, challenge passage, and subsequent-request verification.

Flint's own HMAC challenges and cookies are separate from Cloudflare's tokens and `cf_clearance`; no Cloudflare service is impersonated.
