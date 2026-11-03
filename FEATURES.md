# Features researched and implemented

The official documentation websites returned HTTP 403 in this environment. Research used the projects' official documentation source repositories on GitHub instead.

| Source | Documented behavior | FlintWS implementation |
| --- | --- | --- |
| [NGINX proxy module](https://github.com/nginx/nginx.org/blob/master/xml/en/docs/http/ngx_http_proxy_module.xml) | HTTP upstream proxying, header control, timeouts | Streaming HTTP/HTTPS upstream requests, hop-by-hop header filtering, body limit, timeout responses, client-disconnect cancellation |
| [NGINX upstream module](https://github.com/nginx/nginx.org/blob/master/xml/en/docs/http/ngx_http_upstream_module.xml) | Upstream groups, round-robin distribution | Equal-weight round robin per route, longest path-prefix selection |
| [Apache virtual hosts](https://github.com/apache/httpd/blob/trunk/docs/manual/vhosts/name-based.xml) | Select a document root by hostname | Explicit hostname-to-root mappings; unknown configured hosts return 421 |
| [Apache authentication](https://github.com/apache/httpd/blob/trunk/docs/manual/howto/auth.xml) | Basic authentication and authentication challenges | Basic challenge, environment-backed password, constant-time digest comparison |
| [NGINX request limiting](https://github.com/nginx/nginx.org/blob/master/xml/en/docs/http/ngx_http_limit_req_module.xml) | Limit request processing rate using a leaky bucket | Fixed-window limits keyed by socket IP, 429 with Retry-After, bounded client map |
| [Apache alias module](https://github.com/apache/httpd/blob/trunk/docs/manual/mod/mod_alias.xml) | Configured redirects | Exact path redirects with permanent 308 status |

The rate limiter uses a fixed window, not NGINX's leaky-bucket algorithm. Limits and round-robin state are local to each FlintWS process. Proxy requests are not retried, so POST bodies cannot be replayed automatically. Upstream paths and query strings are preserved; prefixes are matched, not stripped. WebSocket upgrades and CONNECT tunnels are not implemented.

## Configuration

Set `FLINTWS_CONFIG` to a JSON file. Relative document roots resolve from the process working directory. Configuration is loaded once at startup; restart to apply changes. Every configured root must exist. Without a config file, the existing static-server workflow continues to work.

```json
{
  "routes": [
    { "prefix": "/backend", "upstreams": ["http://127.0.0.1:4000", "http://127.0.0.1:4001"] }
  ],
  "redirects": { "/old-home": "/" },
  "rateLimit": { "requests": 120, "windowMs": 60000 },
  "maxBodyBytes": 10485760,
  "proxyTimeoutMs": 30000
}
```

Optional `virtualHosts` maps lowercase hostnames to document roots, for example `{"site.local":"/srv/site"}`. When configured, only those hosts are accepted. Each request passes through host selection, rate limiting, authentication, redirects, and proxy routing before reaching the static server.

Optional `auth` has the shape `{"username":"reader","passwordEnv":"SITE_PASSWORD"}`. Inject the password through the named environment variable. Startup fails if it is absent. Authentication applies to all routes, including health checks; configure authenticated probes when enabling it. Basic authentication requires HTTPS at the ingress. Gateway credentials are removed before proxying to upstream applications.

The gateway replaces client-supplied Forwarded and X-Forwarded-* headers with the actual socket address and transport. Rate limiting uses that address, so a reverse proxy in front of FlintWS shares one limit unless it presents distinct client socket addresses. Redirect destinations are explicit configuration values. The proxy only contacts configured upstream origins, uses TLS certificate verification for HTTPS, and allows arbitrary methods on proxy routes. Static routes remain GET/HEAD only.

The proxy's total deadline includes upload and download time; expired connections return 504 before response headers, or terminate the stream after headers. Unreachable upstreams return 502. Oversized uploads return 413. Successful response bodies stream with backpressure.
