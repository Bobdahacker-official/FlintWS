import http from 'node:http';
import https from 'node:https';
import { createHash, timingSafeEqual } from 'node:crypto';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const hopHeaders = ['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'];
function cleanHeaders(headers) {
  const cleaned = { ...headers };
  for (const name of [...hopHeaders, ...String(headers.connection ?? '').toLowerCase().split(',').map(value => value.trim())]) delete cleaned[name];
  return cleaned;
}
function reply(response, status, message, headers = {}) {
  response.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', ...headers });
  response.end(message);
}
const digest = value => createHash('sha256').update(value).digest();

export function createGateway(config = {}) {
  if (!config || typeof config !== 'object' || Array.isArray(config)) throw new Error('Gateway config must be an object');
  const { virtualHosts = {}, redirects = {}, routes = [], auth, rateLimit } = config;
  for (const mapping of [virtualHosts, redirects]) if (!mapping || typeof mapping !== 'object' || Array.isArray(mapping)) throw new Error('virtualHosts and redirects must be objects');
  if (!Array.isArray(routes)) throw new Error('routes must be an array');
  if (auth && (typeof auth.username !== 'string' || !auth.username || !auth.passwordEnv || !process.env[auth.passwordEnv])) {
    throw new Error('auth requires username and a populated passwordEnv');
  }
  const credential = auth ? digest(`${auth.username}:${process.env[auth.passwordEnv]}`) : null;
  for (const [hostname, root] of Object.entries(virtualHosts)) {
    if (!/^[a-z0-9.-]+$/.test(hostname) || typeof root !== 'string' || !root) throw new Error('virtualHosts maps lowercase hostnames to document roots');
  }
  for (const [pathname, destination] of Object.entries(redirects)) {
    if (!pathname.startsWith('/') || typeof destination !== 'string' || !destination.startsWith('/') && !/^https?:\/\//.test(destination)) throw new Error('redirects must map paths to paths or HTTP URLs');
    http.validateHeaderValue('Location', destination);
  }
  const prepared = routes.map(route => {
    if (!route || typeof route.prefix !== 'string' || !route.prefix.startsWith('/') || /[?#]/.test(route.prefix) || !Array.isArray(route.upstreams) || !route.upstreams.length) throw new Error('Each route needs a path prefix and nonempty upstreams');
    const upstreams = route.upstreams.map(value => {
      const url = new URL(value);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error('upstreams must be HTTP origins without credentials or paths');
      return url;
    });
    return { ...route, upstreams, next: 0 };
  }).sort((a, b) => b.prefix.length - a.prefix.length);
  const timeoutMs = config.proxyTimeoutMs ?? 30000;
  const maxBodyBytes = config.maxBodyBytes ?? 10485760;
  for (const value of [timeoutMs, maxBodyBytes]) if (!Number.isSafeInteger(value) || value <= 0) throw new Error('Proxy limits must be positive integers');
  if (rateLimit && (!Number.isSafeInteger(rateLimit.requests) || rateLimit.requests < 1 || !Number.isSafeInteger(rateLimit.windowMs) || rateLimit.windowMs < 1)) throw new Error('rateLimit requires positive integer requests and windowMs');
  const clients = new Map();
  let lastSweep = 0;

  return async function gateway(request, response, pathname) {
    let hostname;
    try { hostname = new URL(`http://${request.headers.host ?? ''}`).hostname.toLowerCase(); }
    catch { reply(response, 400, 'Invalid Host'); return { handled: true }; }
    if (Object.keys(virtualHosts).length && !Object.hasOwn(virtualHosts, hostname)) {
      reply(response, 421, 'Misdirected Request');
      return { handled: true };
    }
    if (rateLimit) {
      const now = Date.now();
      if (now - lastSweep >= rateLimit.windowMs) {
        for (const [key, value] of clients) if (value.reset <= now) clients.delete(key);
        lastSweep = now;
      }
      const key = request.socket.remoteAddress;
      let client = clients.get(key);
      if (!client || client.reset <= now) {
        if (clients.size >= 10000 && !clients.has(key)) {
          reply(response, 503, 'Rate limiter capacity reached');
          return { handled: true };
        }
        client = { count: 0, reset: now + rateLimit.windowMs };
        clients.set(key, client);
      }
      if (++client.count > rateLimit.requests) {
        reply(response, 429, 'Too Many Requests', { 'Retry-After': Math.ceil((client.reset - now) / 1000), Connection: 'close' });
        return { handled: true };
      }
    }
    if (credential) {
      const supplied = /^Basic ([A-Za-z0-9+/]+=*)$/i.exec(request.headers.authorization ?? '');
      if (!supplied || !timingSafeEqual(credential, digest(Buffer.from(supplied[1], 'base64').toString('utf8')))) {
        reply(response, 401, 'Authentication required', { 'WWW-Authenticate': 'Basic realm="FlintWS", charset="UTF-8"', Connection: 'close' });
        return { handled: true };
      }
    }
    if (Object.hasOwn(redirects, pathname)) {
      response.writeHead(308, { Location: redirects[pathname], 'Content-Length': 0 });
      response.end();
      return { handled: true };
    }
    const route = prepared.find(route => pathname === route.prefix || pathname.startsWith(route.prefix.endsWith('/') ? route.prefix : `${route.prefix}/`));
    if (route) {
      const target = route.upstreams[route.next];
      route.next = (route.next + 1) % route.upstreams.length;
      if (Number(request.headers['content-length'] ?? 0) > maxBodyBytes) {
        reply(response, 413, 'Payload Too Large', { Connection: 'close' });
        return { handled: true };
      }
      const headers = cleanHeaders(request.headers);
      for (const key of Object.keys(headers)) if (key.startsWith('x-forwarded-') || key === 'forwarded') delete headers[key];
      headers.host = target.host;
      headers['x-forwarded-for'] = request.socket.remoteAddress;
      headers['x-forwarded-proto'] = request.socket.encrypted ? 'https' : 'http';
      // Local credentials authorize this gateway, never an upstream application.
      if (credential) delete headers.authorization;
      await new Promise(resolve => {
        let timedOut = false;
        let tooLarge = false;
        const upstream = (target.protocol === 'https:' ? https : http).request(target, {
          method: request.method, path: request.url, headers
        });
        const timer = setTimeout(() => { timedOut = true; upstream.destroy(); }, timeoutMs);
        const disconnected = () => upstream.destroy();
        response.once('close', disconnected);
        const finish = () => {
          clearTimeout(timer);
          response.removeListener('close', disconnected);
          resolve();
        };
        upstream.on('response', async incoming => {
          try {
            response.writeHead(incoming.statusCode, cleanHeaders(incoming.headers));
            await pipeline(incoming, response);
          } catch (error) { response.destroy(error); }
          finally { finish(); }
        });
        upstream.on('error', () => {
          if (!response.headersSent && !response.destroyed) reply(response, tooLarge ? 413 : timedOut ? 504 : 502, tooLarge ? 'Payload Too Large' : timedOut ? 'Gateway Timeout' : 'Bad Gateway', { Connection: 'close' });
          else response.destroy();
          finish();
        });
        let bytes = 0;
        const limiter = new Transform({ transform(chunk, encoding, callback) {
          bytes += chunk.length;
          if (bytes > maxBodyBytes) {
            tooLarge = true;
            callback(new Error('Payload Too Large'));
          } else callback(null, chunk);
        } });
        // Preserve the client socket long enough to deliver an upstream error.
        request.pipe(limiter).pipe(upstream);
        const abort = () => upstream.destroy();
        request.once('aborted', abort);
        limiter.once('error', abort);
        upstream.once('close', () => {
          request.unpipe(limiter);
          limiter.destroy();
          request.removeListener('aborted', abort);
        });
      });
      return { handled: true };
    }
    return { handled: false, root: virtualHosts[hostname] };
  };
}
