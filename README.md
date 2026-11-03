# FlintWS

FlintWS is a small HTTP server for serving a static web root with operational endpoints.

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

## Verify it

```sh
npm test
npm run check
```
