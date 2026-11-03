import http from 'node:http';
import { constants } from 'node:fs';
import { open, readFile, realpath, stat } from 'node:fs/promises';
import { createGateway } from './gateway.js';
import { pipeline } from 'node:stream/promises';
import { createBrotliCompress, createGzip, constants as zlibConstants } from 'node:zlib';
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

function requestPath(requestUrl) {
  let pathname;
  try {
    const parsed = new URL(requestUrl, 'http://flintws.local');
    pathname = decodeURIComponent(parsed.pathname);
  } catch {
    const error = new Error('Malformed URL');
    error.statusCode = 400;
    throw error;
  }
  if (pathname.includes('\0') || pathname.includes('\\')) {
    const error = new Error('Invalid path');
    error.statusCode = 400;
    throw error;
  }
  return pathname;
}

async function resolvePublicFile(root, pathname) {
  if (pathname.split('/').some(segment => segment.startsWith('.'))) return null;
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
    return { filePath: candidate, details: indexDetails, directory: true };
  }
  if (!details.isFile()) return null;
  return { filePath: candidate, details };
}

function etagFor(details) {
  return `W/"${details.size.toString(16)}-${details.mtimeMs.toString(16)}"`;
}

async function serveStatic(response, root, pathname, method, requestHeaders, { spa, cacheSeconds }) {
  let resolved = await resolvePublicFile(root, pathname === '/' ? '/index.html' : pathname);
  if (!resolved && spa && !path.extname(pathname) && !pathname.split('/').some(part => part.startsWith('.'))) {
    resolved = await resolvePublicFile(root, '/index.html');
  }
  if (!resolved) {
    sendJson(response, 404, { error: 'Not Found', path: pathname }, method);
    return 404;
  }

  if (resolved.directory && !pathname.endsWith('/')) {
    response.writeHead(308, { Location: encodeURI(pathname + '/'), 'Content-Length': 0 });
    response.end();
    return 308;
  }

  const { filePath } = resolved;
  const file = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const details = await file.stat();
    if (!details.isFile()) {
      sendJson(response, 404, { error: 'Not Found' }, method);
      return 404;
    }
    const etag = etagFor(details);
    const headers = {
      'Content-Type': MIME_TYPES.get(path.extname(filePath).toLowerCase()) ?? 'application/octet-stream',
      'Content-Length': details.size,
      'Cache-Control': `public, max-age=${cacheSeconds}, must-revalidate`,
      'Accept-Ranges': 'bytes',
      ETag: etag,
      'Last-Modified': details.mtime.toUTCString(),
      'X-Content-Type-Options': 'nosniff'
    };
    const compressible = /^(text\/|application\/(json|javascript|xml)|image\/svg\+xml)/.test(headers['Content-Type']);
    let encoding;
    if (compressible) {
      headers.Vary = 'Accept-Encoding';
      if (!requestHeaders.range && details.size >= 1024) {
        const accepted = String(requestHeaders['accept-encoding'] ?? '').split(',').map(value => {
          const [name, ...parameters] = value.trim().toLowerCase().split(';');
          const q = parameters.find(value => value.trim().startsWith('q='));
          const quality = q ? Number(q.trim().slice(2)) : 1;
          return [name, Number.isFinite(quality) && quality >= 0 && quality <= 1 ? quality : 0];
        });
        const quality = name => accepted.find(([coding]) => coding === name)?.[1] ?? accepted.find(([coding]) => coding === '*')?.[1] ?? 0;
        if (quality('br') > 0 || quality('gzip') > 0) encoding = quality('br') >= quality('gzip') ? 'br' : 'gzip';
      }
    }

    const match = requestHeaders['if-none-match'];
    const fresh = match
      ? match.split(',').some(tag => tag.trim() === '*' || tag.trim().replace(/^W\//, '') === etag.replace(/^W\//, ''))
      : requestHeaders['if-modified-since'] && Math.floor(details.mtimeMs / 1000) <= Math.floor(Date.parse(requestHeaders['if-modified-since']) / 1000);
    if (fresh) {
      response.writeHead(304, { ETag: etag, 'Last-Modified': headers['Last-Modified'], 'Cache-Control': headers['Cache-Control'], ...(headers.Vary ? { Vary: headers.Vary } : {}) });
      response.end();
      return 304;
    }

    let status = 200;
    let range;
    const rangeHeader = requestHeaders.range;
    const ifRange = requestHeaders['if-range'];
    if (method === 'GET' && rangeHeader && (!ifRange || (!ifRange.includes('"') && Date.parse(ifRange) >= Math.floor(details.mtimeMs / 1000) * 1000))) {
      const parsed = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader);
      if (parsed && (parsed[1] || parsed[2])) {
        const start = parsed[1] ? Number(parsed[1]) : Math.max(0, details.size - Number(parsed[2]));
        const end = parsed[1] && parsed[2] ? Math.min(Number(parsed[2]), details.size - 1) : details.size - 1;
        if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end || start >= details.size) {
          response.writeHead(416, { 'Content-Range': `bytes */${details.size}`, 'Content-Length': 0 });
          response.end();
          return 416;
        }
        range = { start, end };
        status = 206;
        headers['Content-Range'] = `bytes ${start}-${end}/${details.size}`;
        headers['Content-Length'] = end - start + 1;
      }
    }
    if (encoding) {
      headers['Content-Encoding'] = encoding;
      delete headers['Content-Length'];
    }
    response.writeHead(status, headers);
    if (method === 'HEAD') {
      response.end();
      return 200;
    }

    const source = file.createReadStream({ ...range, autoClose: false });
    if (encoding) {
      const compressor = encoding === 'br'
        ? createBrotliCompress({ params: { [zlibConstants.BROTLI_PARAM_QUALITY]: 4 } })
        : createGzip({ level: 6 });
      await pipeline(source, compressor, response);
    } else await pipeline(source, response);
    return status;
  } finally {
    await file.close();
  }
}

export function createFlintServer({ root = process.env.FLINTWS_ROOT ?? defaultPublicRoot, logger = console,
  spa = process.env.FLINTWS_SPA === 'true', cacheSeconds = Number(process.env.FLINTWS_CACHE_SECONDS ?? 0),
  corsOrigin = process.env.FLINTWS_CORS_ORIGIN, config = {} } = {}) {
  if (!Number.isSafeInteger(cacheSeconds) || cacheSeconds < 0) throw new Error('FLINTWS_CACHE_SECONDS must be a nonnegative integer');
  if (corsOrigin) http.validateHeaderValue('Access-Control-Allow-Origin', corsOrigin);
  const publicRoot = path.resolve(root);
  const gateway = createGateway(config);
  const server = http.createServer({ maxHeaderSize: 16384, headersTimeout: 15000, requestTimeout: 30000, keepAliveTimeout: 5000 }, async (request, response) => {
    const startedAt = process.hrtime.bigint();
    const method = request.method ?? 'GET';
    let statusCode = 500;
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    response.once('finish', () => logger.info(JSON.stringify({
      method, path: request.url, status: response.statusCode,
      durationMs: Number((Number(process.hrtime.bigint() - startedAt) / 1e6).toFixed(2))
    })));
    try {
      const pathname = requestPath(request.url ?? '/');
      const dispatch = await gateway(request, response, pathname);
      if (dispatch.handled) return;
      if (corsOrigin) {
        response.setHeader('Access-Control-Allow-Origin', corsOrigin);
        if (method === 'OPTIONS') {
          response.writeHead(204, { 'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS', 'Access-Control-Allow-Headers': 'Range, If-None-Match, If-Modified-Since', 'Access-Control-Max-Age': '600' });
          response.end();
          return;
        }
      }
      if (method !== 'GET' && method !== 'HEAD') {
        response.setHeader('Allow', 'GET, HEAD');
        response.setHeader('Connection', 'close');
        sendJson(response, 405, { error: 'Method Not Allowed' }, method);
        statusCode = 405;
        return;
      }

      if (pathname === '/healthz') {
        sendJson(response, 200, { status: 'ok', service: SERVER_NAME, version: SERVER_VERSION }, method);
        statusCode = 200;
        return;
      }
      if (pathname === '/api/info') {
        sendJson(response, 200, {
          name: SERVER_NAME,
          version: SERVER_VERSION,
        }, method);
        statusCode = 200;
        return;
      }
      statusCode = await serveStatic(response, dispatch.root ?? publicRoot, pathname, method, request.headers, { spa, cacheSeconds });
    } catch (error) {
      statusCode = error.statusCode ?? (['ENOENT', 'ENOTDIR', 'EACCES', 'ELOOP'].includes(error.code) ? 404 : 500);
      if (statusCode === 500 && error.code !== 'ERR_STREAM_PREMATURE_CLOSE') logger.error?.(JSON.stringify({ error: error.message, code: error.code }));
      if (!response.headersSent) {
        sendJson(response, statusCode, {
          error: statusCode === 400 ? error.message : statusCode === 404 ? 'Not Found' : 'Internal Server Error'
        }, method);
      } else {
        response.destroy(error);
      }
    }
  });
  server.maxRequestsPerSocket = 1000;
  server.maxConnections = 1024;
  server.setTimeout(30000, socket => socket.destroy());
  return server;
}

export async function startServer({ host = process.env.HOST ?? DEFAULT_HOST, port = Number(process.env.PORT ?? DEFAULT_PORT), root = process.env.FLINTWS_ROOT ?? defaultPublicRoot, logger, ...options } = {}) {
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('PORT must be an integer between 0 and 65535');
  const publicRoot = await realpath(root);
  if (!(await stat(publicRoot)).isDirectory()) throw new Error('FLINTWS_ROOT must be a directory');
  const config = options.config ?? (process.env.FLINTWS_CONFIG ? JSON.parse(await readFile(process.env.FLINTWS_CONFIG, 'utf8')) : {});
  for (const documentRoot of Object.values(config.virtualHosts ?? {})) {
    if (!(await stat(documentRoot)).isDirectory()) throw new Error('Virtual host document roots must be directories');
  }
  const server = createFlintServer({ ...options, config, root: publicRoot, logger });
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
  let stopping = false;
  const shutdown = () => {
    if (stopping) return;
    stopping = true;
    const deadline = setTimeout(() => server.closeAllConnections(), 10000);
    deadline.unref();
    server.close(() => clearTimeout(deadline));
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}
