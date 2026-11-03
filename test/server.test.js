import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { startServer } from '../server.js';

async function runningServer(options = {}) {
  const root = await mkdtemp(path.join(tmpdir(), 'flintws-'));
  await writeFile(path.join(root, 'index.html'), '<h1>FlintWS test page</h1>');
  await writeFile(path.join(root, 'data.json'), '{"ready":true}');
  await writeFile(path.join(root, 'large.txt'), 'FlintWS compression sample.\n'.repeat(200));
  await mkdir(path.join(root, 'docs'));
  await writeFile(path.join(root, 'docs', 'index.html'), 'Documentation');
  const server = await startServer({ ...options, config: { security: { enabled: false }, ...options.config }, host: '127.0.0.1', port: 0, root, logger: { info() {} } });
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;
  return { server, baseUrl, root };
}

test('serves the index document and content metadata', async (t) => {
  const { server, baseUrl } = await runningServer();
  t.after(() => server.close());
  const response = await fetch(`${baseUrl}/`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-type'), 'text/html; charset=utf-8');
  assert.match(await response.text(), /FlintWS test page/);
  assert.ok(response.headers.get('etag'));
});

test('negotiates compression and serves SPA routes, directory redirects, and CORS', async (t) => {
  const { server, baseUrl } = await runningServer({ spa: true, cacheSeconds: 300, corsOrigin: 'https://app.example.com' });
  t.after(() => server.close());
  for (const encoding of ['br', 'gzip']) {
    const response = await fetch(`${baseUrl}/large.txt`, { headers: { 'Accept-Encoding': encoding } });
    assert.equal(response.headers.get('content-encoding'), encoding);
    assert.equal(response.headers.get('vary'), 'Accept-Encoding');
    assert.equal(await response.text(), 'FlintWS compression sample.\n'.repeat(200));
  }
  const route = await fetch(`${baseUrl}/dashboard`);
  assert.equal(route.status, 200);
  assert.match(await route.text(), /FlintWS test page/);
  assert.equal(route.headers.get('cache-control'), 'public, max-age=300, must-revalidate');
  assert.equal((await fetch(`${baseUrl}/missing.js`)).status, 404);
  const redirect = await fetch(`${baseUrl}/docs`, { redirect: 'manual' });
  assert.equal(redirect.status, 308);
  assert.equal(redirect.headers.get('location'), '/docs/');
  const preflight = await fetch(baseUrl, { method: 'OPTIONS' });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get('access-control-allow-origin'), 'https://app.example.com');
});

test('returns health and service information as JSON', async (t) => {
  const { server, baseUrl } = await runningServer();
  t.after(() => server.close());
  const health = await fetch(`${baseUrl}/healthz`);
  assert.deepEqual(await health.json(), { status: 'ok', service: 'FlintWS', version: '1.0.0' });
  const info = await fetch(`${baseUrl}/api/info`);
  const payload = await info.json();
  assert.equal(info.status, 200);
  assert.equal(payload.name, 'FlintWS');
  assert.equal(payload.version, '1.0.0');
  assert.equal(payload.runtime, undefined);
  assert.equal(payload.pid, undefined);
});

test('supports HEAD and conditional requests', async (t) => {
  const { server, baseUrl } = await runningServer();
  t.after(() => server.close());
  const first = await fetch(`${baseUrl}/data.json`);
  const etag = first.headers.get('etag');
  const head = await fetch(`${baseUrl}/data.json`, { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(await head.text(), '');
  assert.equal(head.headers.get('content-length'), '14');
  const cached = await fetch(`${baseUrl}/data.json`, { headers: { 'If-None-Match': etag } });
  assert.equal(cached.status, 304);
  assert.equal(await cached.text(), '');
  const partial = await fetch(`${baseUrl}/data.json`, { headers: { Range: 'bytes=0-6' } });
  assert.equal(partial.status, 206);
  assert.equal(partial.headers.get('content-range'), 'bytes 0-6/14');
  assert.equal(await partial.text(), '{"ready');
  const invalid = await fetch(`${baseUrl}/data.json`, { headers: { Range: 'bytes=99-' } });
  assert.equal(invalid.status, 416);
  const modified = await fetch(`${baseUrl}/data.json`, { headers: { 'If-Modified-Since': first.headers.get('last-modified') } });
  assert.equal(modified.status, 304);
});

test('rejects unsupported methods, missing files, and traversal attempts', async (t) => {
  const { server, baseUrl } = await runningServer();
  t.after(() => server.close());
  const method = await fetch(`${baseUrl}/`, { method: 'POST' });
  assert.equal(method.status, 405);
  assert.equal(method.headers.get('allow'), 'GET, HEAD');
  const missing = await fetch(`${baseUrl}/missing.txt`);
  assert.equal(missing.status, 404);
  const traversal = await fetch(`${baseUrl}/%2e%2e/server.js`);
  assert.equal(traversal.status, 404);
  assert.equal((await fetch(`${baseUrl}/.env`)).status, 404);
});

test('proxies request bodies, balances upstreams, filters forwarded headers and enforces limits', async (t) => {
  const origins = [];
  for (const name of ['one', 'two']) {
    const backend = http.createServer(async (req, res) => {
      if (req.url === '/service/slow') return;
      let body = '';
      for await (const chunk of req) body += chunk;
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ name, body, url: req.url, forwarded: req.headers['x-forwarded-for'], credential: req.headers.authorization }));
    });
    await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
    origins.push(`http://127.0.0.1:${backend.address().port}`);
    t.after(() => { backend.closeAllConnections(); backend.close(); });
  }
  const { server, baseUrl } = await runningServer({ config: {
    routes: [{ prefix: '/service', upstreams: origins }], proxyTimeoutMs: 200, maxBodyBytes: 32
  } });
  t.after(() => { server.closeAllConnections(); server.close(); });
  for (const name of ['one', 'two']) {
    const response = await fetch(`${baseUrl}/service/echo?q=1`, {
      method: 'POST', body: 'real body', headers: { 'X-Forwarded-For': 'spoofed' }
    });
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.name, name);
    assert.equal(result.body, 'real body');
    assert.equal(result.url, '/service/echo?q=1');
    assert.equal(result.forwarded, '127.0.0.1');
  }
  assert.equal((await fetch(`${baseUrl}/service/echo`, { method: 'POST', body: 'x'.repeat(33) })).status, 413);
  assert.equal((await fetch(`${baseUrl}/service/slow`)).status, 504);
  assert.equal((await fetch(`${baseUrl}/services`)).status, 404);
});

test('virtual hosts, redirects, authentication and rate limiting are configurable', async (t) => {
  const site = await runningServer();
  t.after(() => site.server.close());
  const hosted = await runningServer({ config: { virtualHosts: { 'site.local': site.root }, redirects: { '/old': '/new' } } });
  t.after(() => hosted.server.close());
  const host = { Host: 'site.local' };
  const hostRequest = pathname => new Promise((resolve, reject) => {
    http.get(`${hosted.baseUrl}${pathname}`, { headers: host }, response => {
      response.resume();
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers }));
    }).on('error', reject);
  });
  assert.equal((await hostRequest('/')).status, 200);
  assert.equal((await fetch(hosted.baseUrl)).status, 421);
  const redirect = await hostRequest('/old');
  assert.equal(redirect.status, 308);
  assert.equal(redirect.headers.location, '/new');
  process.env.FLINTWS_TEST_PASSWORD = 'test-only-secret';
  t.after(() => { delete process.env.FLINTWS_TEST_PASSWORD; });
  const secured = await runningServer({ config: { auth: { username: 'reader', passwordEnv: 'FLINTWS_TEST_PASSWORD' } } });
  t.after(() => secured.server.close());
  const denied = await fetch(secured.baseUrl);
  assert.equal(denied.status, 401);
  assert.match(denied.headers.get('www-authenticate'), /^Basic/);
  assert.equal((await fetch(secured.baseUrl, { headers: { Authorization: `Basic ${Buffer.from('reader:test-only-secret').toString('base64')}` } })).status, 200);
  const limited = await runningServer({ config: { rateLimit: { requests: 1, windowMs: 60000 } } });
  t.after(() => limited.server.close());
  assert.equal((await fetch(limited.baseUrl)).status, 200);
  const blocked = await fetch(limited.baseUrl);
  assert.equal(blocked.status, 429);
  assert.ok(Number(blocked.headers.get('retry-after')) > 0);
});

test('FlintSecurity gates content, verifies unique work, rejects replay and binds clearance', async (t) => {
  const { server, baseUrl } = await runningServer({ config: { security: { difficulty: 12, clearanceSeconds: 30 } } });
  t.after(() => server.close());
  const page = await fetch(baseUrl, { headers: { Accept: 'text/html' } });
  assert.equal(page.status, 403);
  assert.equal(page.headers.get('cache-control'), 'private, no-store');
  const html = await page.text();
  assert.match(html, /This site uses flint security to verify connections from other parties/);
  assert.match(html, /aria-label="Stop"/);
  assert.doesNotMatch(html, /FlintWS test page/);
  const encoded = /name="flint-security" content="([^"]+)"/.exec(html)[1];
  const data = JSON.parse(Buffer.from(encoded, 'base64url'));
  const verify = (body, origin = baseUrl) => fetch(`${baseUrl}/__flint_security/verify`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Origin: origin }, body: JSON.stringify(body)
  });
  assert.equal((await fetch(`${baseUrl}/data.json`)).status, 403);
  assert.equal((await fetch(`${baseUrl}/healthz`)).status, 200);
  assert.equal((await fetch(`${baseUrl}/__flint_security/client.js`)).status, 200);
  assert.equal((await verify({ challenge: data.challenge, counter: 0 }, 'https://outside.invalid')).status, 403);
  assert.equal((await verify({ challenge: `${data.challenge}tampered`, counter: 0 })).status, 403);
  let counter = 0;
  for (;;) {
    const hash = createHash('sha256').update(`${data.challenge}:${counter}`).digest();
    const bytes = Math.floor(data.bits / 8);
    if (hash.subarray(0, bytes).every(value => value === 0) && (data.bits % 8 === 0 || hash[bytes] < 2 ** (8 - data.bits % 8))) break;
    counter++;
  }
  const passed = await verify({ challenge: data.challenge, counter });
  assert.equal(passed.status, 200);
  assert.equal((await passed.json()).verified, true);
  const setCookie = passed.headers.get('set-cookie');
  assert.match(setCookie, /HttpOnly; SameSite=Strict; Max-Age=30/);
  const cookie = setCookie.split(';')[0];
  assert.equal((await verify({ challenge: data.challenge, counter })).status, 403);
  const allowed = await fetch(baseUrl, { headers: { Cookie: cookie } });
  assert.equal(allowed.status, 200);
  assert.match(await allowed.text(), /FlintWS test page/);
  assert.equal((await fetch(baseUrl, { headers: { Cookie: cookie, 'User-Agent': 'different-client' } })).status, 403);
  assert.equal((await fetch(baseUrl, { headers: { Cookie: `${cookie}tampered` } })).status, 403);
});

test('FlintSecurity blocks upstream requests before they reach the application', async (t) => {
  let reached = 0;
  const backend = http.createServer((req, res) => { reached++; res.end('protected'); });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  t.after(() => backend.close());
  const { server, baseUrl } = await runningServer({ config: { security: {}, routes: [{ prefix: '/private', upstreams: [`http://127.0.0.1:${backend.address().port}`] }] } });
  t.after(() => server.close());
  assert.equal((await fetch(`${baseUrl}/private`, { method: 'POST', body: 'bypass attempt' })).status, 403);
  assert.equal(reached, 0);
});
