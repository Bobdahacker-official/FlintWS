import http from 'node:http';
import { createReadStream } from 'node:fs';
import { access, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SERVER_NAME = 'FlintWS';
const SERVER_VERSION = '1.0.0';
const DEFAULT_PORT = 3000;
const DEFAULT_HOST = '0.0.0.0';
const moduleRoot = path.dirname(fileURLToPath(import.meta.url));
const defaultPublicRoot = path.join(moduleRoot, 'public');

const MIME_TYPES = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.gif', 'image/gif'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.jpeg', 'image/jpeg'],
  ['.jpg', 'image/jpeg'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml'],
  ['.txt', 'text/plain; charset=utf-8'],
  ['.webp', 'image/webp'],
  ['.woff', 'font/woff'],
  ['.woff2', 'font/woff2']
]);

function isInside(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

function sendJson(response, statusCode, payload, method) {
  const body = JSON.stringify(payload);
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  if (method !== 'HEAD') response.end(body);
  else response.end();
}

function sendText(response, statusCode, body, method, contentType = 'text/plain; charset=utf-8') {
  response.writeHead(statusCode, {
    'Content-Type': contentType,
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  if (method !== 'HEAD') response.end(body);
  else response.end();
}

function requestPath(requestUrl) {
  const parsed = new URL(requestUrl, 'http://flintws.local');
  let pathname;
  try {
    pathname = decodeURIComponent(parsed.pathname);
  } catch {
    const error = new Error('Malformed URL');
    error.statusCode = 400;
    throw error;
  }
  if (pathname.includes('\0')) {
    const error = new Error('Invalid path');
    error.statusCode = 400;
    throw error;
  }
  return pathname;
}

async function resolvePublicFile(root, pathname) {
  const rootRealPath = await realpath(root);
  const requested = path.resolve(rootRealPath, `.${pathname}`);
  if (!isInside(rootRealPath, requested)) return null;

  let candidate;
  try {
    candidate = await realpath(requested);
  } catch (error) {
    if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return null;
    throw error;
  }
  if (!isInside(rootRealPath, candidate)) return null;

  const details = await stat(candidate);
  if (details.isDirectory()) {
    const indexFile = path.join(candidate, 'index.html');
    try {
      candidate = await realpath(indexFile);
    } catch (error) {
      if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return null;
      throw error;
    }
    if (!isInside(rootRealPath, candidate)) return null;
    const indexDetails = await stat(candidate);
    if (!indexDetails.isFile()) return null;
    return { filePath: candidate, details: indexDetails };
  }
  if (!details.isFile()) return null;
  return { filePath: candidate, details };
}

function etagFor(details) {
  return `"${details.size.toString(16)}-${Math.floor(details.mtimeMs).toString(16)}"`;
}

async function serveStatic(response, root, pathname, method, requestHeaders) {
  const resolved = await resolvePublicFile(root, pathname === '/' ? '/index.html' : pathname);
  if (!resolved) {
    sendJson(response, 404, { error: 'Not Found', path: pathname }, method);
    return 404;
  }

  const { filePath, details } = resolved;
  const etag = etagFor(details);
  const headers = {
    'Content-Type': MIME_TYPES.get(path.extname(filePath).toLowerCase()) ?? 'application/octet-stream',
    'Content-Length': details.size,
    'Cache-Control': 'public, max-age=300',
    ETag: etag,
    'Last-Modified': details.mtime.toUTCString(),
    'X-Content-Type-Options': 'nosniff'
  };

  if (requestHeaders['if-none-match'] === etag) {
    response.writeHead(304, { ETag: etag, 'Cache-Control': headers['Cache-Control'] });
    response.end();
    return 304;
  }

  response.writeHead(200, headers);
  if (method === 'HEAD') {
    response.end();
    return 200;
  }

  await new Promise((resolve, reject) => {
    const stream = createReadStream(filePath);
    stream.once('error', reject);
    stream.once('end', resolve);
    stream.pipe(response);
  });
  return 200;
}

export function createFlintServer({ root = process.env.FLINTWS_ROOT ?? defaultPublicRoot, logger = console } = {}) {
  const publicRoot = path.resolve(root);
  const server = http.createServer(async (request, response) => {
    const startedAt = process.hrtime.bigint();
    const method = request.method ?? 'GET';
    let statusCode = 500;
    try {
      if (method !== 'GET' && method !== 'HEAD') {
        response.setHeader('Allow', 'GET, HEAD');
        sendJson(response, 405, { error: 'Method Not Allowed' }, method);
        statusCode = 405;
        return;
      }

      const pathname = requestPath(request.url ?? '/');
      if (pathname === '/healthz') {
        sendJson(response, 200, { status: 'ok', service: SERVER_NAME, version: SERVER_VERSION }, method);
        statusCode = 200;
        return;
      }
      if (pathname === '/api/info') {
        sendJson(response, 200, {
          name: SERVER_NAME,
          version: SERVER_VERSION,
          runtime: process.version,
          pid: process.pid
        }, method);
        statusCode = 200;
        return;
      }
      statusCode = await serveStatic(response, publicRoot, pathname, method, request.headers);
    } catch (error) {
      statusCode = error.statusCode ?? 500;
      if (!response.headersSent) {
        sendJson(response, statusCode, {
          error: statusCode === 400 ? error.message : 'Internal Server Error'
        }, method);
      } else {
        response.destroy(error);
      }
    } finally {
      const elapsedMs = Number(process.hrtime.bigint() - startedAt) / 1e6;
      logger.info(`${method} ${request.url ?? '/'} ${statusCode} ${elapsedMs.toFixed(1)}ms`);
    }
  });
  return server;
}

export function startServer({ host = process.env.HOST ?? DEFAULT_HOST, port = Number(process.env.PORT ?? DEFAULT_PORT), root, logger } = {}) {
  const server = createFlintServer({ root, logger });
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      server.removeListener('listening', onListening);
      reject(error);
    };
    const onListening = () => {
      server.removeListener('error', onError);
      resolve(server);
    };
    server.once('error', onError);
    server.once('listening', onListening);
    server.listen(port, host);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = await startServer();
  const address = server.address();
  const displayHost = typeof address === 'object' && address ? address.address : process.env.HOST ?? DEFAULT_HOST;
  const displayPort = typeof address === 'object' && address ? address.port : process.env.PORT ?? DEFAULT_PORT;
  console.log(`${SERVER_NAME} listening on http://${displayHost}:${displayPort}`);
}
