# FlintWS

FlintWS serves static websites and proxies application traffic with configurable load balancing, virtual hosts, authentication, redirects, and request limits.

See [gateway configuration and research sources](FEATURES.md) for the new routing features. Set `FLINTWS_CONFIG=/path/to/config.json` to load gateway settings at startup.

## Run it

```sh
npm start
```

The server listens on `0.0.0.0:3000` by default. Set `HOST`, `PORT`, or `FLINTWS_ROOT` when a different bind address, port, or public directory is needed.

```sh
PORT=8080 npm start
FLINTWS_ROOT=/srv/site PORT=8080 npm start
```

`GET /healthz` returns a JSON readiness response, and `GET /api/info` returns service metadata. Other GET and HEAD requests serve files from `public/` with MIME types, ETags, conditional responses, and traversal protection.

## Serving features

Text, JSON, JavaScript, and SVG files of at least 1 KiB are compressed with Brotli or gzip according to the client's Accept-Encoding preferences. Range requests retain the original representation so downloads can resume. Compression streams without buffering entire files.

Set `FLINTWS_SPA=true` to serve `index.html` for missing extensionless routes, supporting browser navigation in single-page apps. Missing assets still return 404. Directories with an index redirect to a trailing slash so relative asset links resolve correctly.

Set `FLINTWS_CACHE_SECONDS=300` to permit five minutes of browser caching. The default forces revalidation on each request. Set `FLINTWS_CORS_ORIGIN=https://app.example.com` (or `*` for public resources) to enable cross-origin reads and OPTIONS preflight responses. Credentials are never enabled by the server.

## Deployment

Run on Node.js 24 LTS under a process supervisor, or build the included container:

```sh
docker build -t flintws .
docker run --init --read-only --cap-drop=ALL --security-opt=no-new-privileges -p 3000:3000 flintws
```

The container runs as the unprivileged `node` user and checks `/healthz`. Put a TLS-terminating reverse proxy in front of the HTTP listener. Use a dedicated, read-only public directory containing only files intended for publication; directory listings and dotfiles are blocked. Keep that directory immutable while the process runs and deploy replacements by restarting the process.

Downloads stream with backpressure and stop when clients disconnect. Single byte ranges support resumable downloads. Files use revalidation caching with weak ETags and Last-Modified timestamps; HTML changes become visible on the next request. Request headers are limited to 16 KiB, headers must arrive within 15 seconds, and requests have a 30-second receive timeout. Idle keep-alive connections close after 5 seconds.

SIGTERM and SIGINT stop accepting connections and allow active requests up to 10 seconds to finish before closing them. Access logs are JSON lines on stdout; server errors go to stderr. Startup fails if the public directory is invalid or the port is outside 0–65535.

## Verify it

```sh
npm test
npm run check
```
