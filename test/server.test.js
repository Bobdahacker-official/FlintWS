import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { startServer } from '../server.js';

async function runningServer(options = {}) {
  const root = await mkdtemp(path.join(tmpdir(), 'flintws-'));
  await writeFile(path.join(root, 'index.html'), '<h1>FlintWS test page</h1>');
  await writeFile(path.join(root, 'data.json'), '{"ready":true}');
  await writeFile(path.join(root, 'large.txt'), 'FlintWS compression sample.\n'.repeat(200));
  await mkdir(path.join(root, 'docs'));
  await writeFile(path.join(root, 'docs', 'index.html'), 'Documentation');
  const server = await startServer({ ...options, host: '127.0.0.1', port: 0, root, logger: { info() {} } });
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;
  return { server, baseUrl };
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
