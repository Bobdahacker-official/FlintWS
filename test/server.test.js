import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { startServer } from '../server.js';

async function runningServer() {
  const root = await mkdtemp(path.join(tmpdir(), 'flintws-'));
  await writeFile(path.join(root, 'index.html'), '<h1>FlintWS test page</h1>');
  await writeFile(path.join(root, 'data.json'), '{"ready":true}');
  const server = await startServer({ host: '127.0.0.1', port: 0, root, logger: { info() {} } });
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
  assert.match(payload.runtime, /^v\d+\.\d+\.\d+$/);
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
});
