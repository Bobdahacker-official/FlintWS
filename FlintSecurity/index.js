import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const prefix = '/__flint_security';
const assets = new Map(await Promise.all([
  ['client.js', 'text/javascript; charset=utf-8'],
  ['worker.js', 'text/javascript; charset=utf-8'],
  ['style.css', 'text/css; charset=utf-8']
].map(async ([file, type]) => [`${prefix}/${file}`, { type, body: await readFile(new URL(file, import.meta.url)) }])));
const template = await readFile(new URL('challenge.html', import.meta.url), 'utf8');
const hash = value => createHash('sha256').update(value).digest('hex');
const equal = (a, b) => {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
};

function send(response, status, body, type = 'application/json; charset=utf-8') {
  response.writeHead(status, {
    'Content-Type': type, 'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY'
  });
  response.end(body);
}
function reject(response, status, error) { send(response, status, JSON.stringify({ error })); }
function positiveInteger(value, min, max, name) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${name} must be between ${min} and ${max}`);
  return value;
}

export function createFlintSecurity(options = {}) {
  if (options.enabled === false) return async () => false;
  const bits = positiveInteger(options.difficulty ?? 16, 12, 22, 'security.difficulty');
  const challengeSeconds = positiveInteger(options.challengeSeconds ?? 120, 10, 300, 'security.challengeSeconds');
  const clearanceSeconds = positiveInteger(options.clearanceSeconds ?? 1800, 30, 86400, 'security.clearanceSeconds');
  const maxClients = positiveInteger(options.maxClients ?? 10000, 10, 100000, 'security.maxClients');
  const signingKey = options.signingKeyEnv ? process.env[options.signingKeyEnv] : null;
  if (options.signingKeyEnv && (!signingKey || Buffer.byteLength(signingKey) < 32)) throw new Error('Security signing key must contain at least 32 bytes');
  const key = signingKey ?? randomBytes(32);
  const siteKey = options.turnstileSiteKeyEnv ? process.env[options.turnstileSiteKeyEnv] : null;
  const secret = options.turnstileSecretEnv ? process.env[options.turnstileSecretEnv] : null;
  if ((options.turnstileSiteKeyEnv || options.turnstileSecretEnv) && (!siteKey || !secret)) throw new Error('Both Turnstile key environment variables must be populated');
  const mode = siteKey ? 'turnstile' : 'proof-of-work';
  const pending = new Map();
  const clients = new Map();
  const sign = payload => {
    const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
    return `${body}.${createHmac('sha256', key).update(body).digest('hex')}`;
  };
  const decode = token => {
    if (typeof token !== 'string' || token.length > 4096) return null;
    const [body, signature, extra] = token.split('.');
    if (!body || extra || !equal(createHmac('sha256', key).update(body).digest('hex'), signature)) return null;
    try { return JSON.parse(Buffer.from(body, 'base64url')); } catch { return null; }
  };
  let sweepAt = 0;

  return async function intercept(request, response, pathname) {
    // The readiness endpoint exposes no protected website content.
    if (pathname === '/healthz' && ['GET', 'HEAD'].includes(request.method)) return false;
    if (assets.has(pathname) && ['GET', 'HEAD'].includes(request.method)) {
      const asset = assets.get(pathname);
      send(response, 200, request.method === 'HEAD' ? '' : asset.body, asset.type);
      return true;
    }
    const now = Date.now();
    if (now >= sweepAt) {
      for (const [id, challenge] of pending) if (challenge.exp <= now) pending.delete(id);
      for (const [ip, client] of clients) if (client.reset <= now) clients.delete(ip);
      sweepAt = now + 10000;
    }
    const ip = request.socket.remoteAddress ?? '';
    const binding = hash(JSON.stringify([ip, request.headers['user-agent'] ?? '', request.headers.host ?? '']));
    const cookie = String(request.headers.cookie ?? '').split(';').map(value => value.trim()).find(value => value.startsWith('flint_clearance='))?.slice('flint_clearance='.length);
    const clearance = decode(cookie);
    if (!pathname.startsWith(prefix) && clearance?.type === 'clearance' && clearance.exp > now && clearance.exp <= now + clearanceSeconds * 1000 && equal(clearance.binding, binding)) return false;

    let client = clients.get(ip);
    if (!client || client.reset <= now) {
      if (clients.size >= maxClients && !clients.has(ip)) { reject(response, 503, 'Verification capacity reached'); return true; }
      client = { count: 0, reset: now + 60000 };
      clients.set(ip, client);
    }
    if (++client.count > 60) {
      response.setHeader('Retry-After', Math.ceil((client.reset - now) / 1000));
      response.setHeader('Connection', 'close');
      reject(response, 429, 'Too many verification requests');
      return true;
    }

    if (pathname === `${prefix}/verify`) {
      if (request.method !== 'POST') { response.setHeader('Allow', 'POST'); reject(response, 405, 'Use POST'); return true; }
      const secure = Boolean(request.socket.encrypted || options.secureCookies);
      const origin = `${secure ? 'https' : 'http'}://${request.headers.host}`;
      if (request.headers.origin !== origin || String(request.headers['content-type']).split(';')[0].trim().toLowerCase() !== 'application/json') {
        reject(response, 403, 'Invalid verification origin or content type'); return true;
      }
      if (Number(request.headers['content-length'] ?? 0) > 8192) {
        response.setHeader('Connection', 'close'); reject(response, 413, 'Verification payload too large'); return true;
      }
      let raw = '';
      try {
        for await (const chunk of request) {
          raw += chunk;
          if (Buffer.byteLength(raw) > 8192) {
            response.setHeader('Connection', 'close'); reject(response, 413, 'Verification payload too large'); return true;
          }
        }
        const body = JSON.parse(raw);
        if (!body || typeof body !== 'object' || Array.isArray(body)) { reject(response, 400, 'Expected a verification object'); return true; }
        const challenge = decode(body.challenge);
        if (!challenge || challenge.type !== 'challenge' || challenge.exp <= Date.now() || !equal(challenge.binding, binding) || !pending.has(challenge.id)) {
          reject(response, 403, 'Challenge expired, consumed, or invalid'); return true;
        }
        // Consume before any asynchronous validation so parallel replays fail.
        pending.delete(challenge.id);
        let verified = false;
        if (mode === 'turnstile') {
          if (typeof body.turnstileToken !== 'string' || body.turnstileToken.length > 2048) { reject(response, 403, 'Invalid verification token'); return true; }
          const validation = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ secret, response: body.turnstileToken, remoteip: ip }),
            signal: AbortSignal.timeout(8000)
          });
          if (!validation.ok) throw new Error('Verification provider unavailable');
          const result = await validation.json();
          verified = result.success === true && result.hostname === new URL(origin).hostname && result.action === 'flint_security' && result.cdata === challenge.id;
        } else if (Number.isSafeInteger(body.counter) && body.counter >= 0) {
          const proof = createHash('sha256').update(`${body.challenge}:${body.counter}`).digest();
          const bytes = Math.floor(challenge.bits / 8);
          verified = proof.subarray(0, bytes).every(value => value === 0) && (challenge.bits % 8 === 0 || proof[bytes] < 2 ** (8 - challenge.bits % 8));
        }
        if (!verified) { reject(response, 403, 'Verification failed. Reload to try again.'); return true; }
        const token = sign({ type: 'clearance', id: randomBytes(16).toString('hex'), binding, exp: Date.now() + clearanceSeconds * 1000 });
        response.setHeader('Set-Cookie', `flint_clearance=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${clearanceSeconds}${secure ? '; Secure' : ''}`);
        send(response, 200, JSON.stringify({ verified: true, expiresIn: clearanceSeconds }));
      } catch (error) {
        reject(response, error instanceof SyntaxError ? 400 : 503, error instanceof SyntaxError ? 'Invalid JSON' : 'Verification unavailable. Reload to try again.');
      }
      return true;
    }
    if (pathname.startsWith(prefix)) { reject(response, 404, 'Not Found'); return true; }
    if (!['GET', 'HEAD'].includes(request.method) || !String(request.headers.accept ?? '').includes('text/html')) {
      response.setHeader('Connection', 'close');
      reject(response, 403, 'Flint Security verification required. Open this site in a browser first.');
      return true;
    }
    if (pending.size >= maxClients) { reject(response, 503, 'Verification capacity reached'); return true; }
    const challenge = {
      type: 'challenge', id: randomBytes(24).toString('hex'), binding,
      bits: Math.min(22, bits + Math.floor(client.count / 15)), exp: now + challengeSeconds * 1000
    };
    pending.set(challenge.id, { exp: challenge.exp });
    const data = Buffer.from(JSON.stringify({ challenge: sign(challenge), bits: challenge.bits, expires: challenge.exp, mode, siteKey, id: challenge.id })).toString('base64url');
    const page = template.replace('__FLINT_DATA__', data);
    response.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self' https://challenges.cloudflare.com; style-src 'self'; worker-src 'self'; connect-src 'self'; frame-src https://challenges.cloudflare.com; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    send(response, 403, request.method === 'HEAD' ? '' : page, 'text/html; charset=utf-8');
    return true;
  };
}
