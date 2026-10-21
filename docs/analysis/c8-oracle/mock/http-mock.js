/* WO-OPS-30 F2b — mock HTTP target for the connector conformance measurement.
 *
 * ONE target for BOTH engines: the live Camunda Connectors bundle (container
 * zbpm-cmp-connectors) and our own connector (the maven container) hit the SAME
 * server by the SAME name, so the request that arrives is byte-identical on both
 * sides and can be compared byte for byte.
 *
 * The route table is NOT written here — it is READ from the canonical case list
 * (zorrobpm-http-connector/src/test/resources/http-connector-cases.json, section
 * "routes"). That file is the single source of truth; the Java mock used by our
 * side reads the very same table. A route can therefore never mean one thing for
 * the live side and another for ours.
 *
 * Endpoints (outside the /f2b/** contract):
 *   GET  /__requests  -> JSON array of every recorded request (method/path/query/headers/body)
 *   POST /__reset     -> clears the recording
 *
 * Usage: node http-mock.js [--port 8080] [--cases <path-to-case-list.json>]
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i > 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

const PORT = parseInt(arg('--port', '8080'), 10);
const DEFAULT_CASES = path.join(__dirname, '..', '..', '..', 'zorrobpm-http-connector',
  'src', 'test', 'resources', 'http-connector-cases.json');
const CASES = arg('--cases', process.env.F2B_CASES || DEFAULT_CASES);

const caseList = JSON.parse(fs.readFileSync(CASES, 'utf8'));
const ROUTES = caseList.routes || {};

/* Recorded requests: the observation channel for "what did each side actually send". */
let recorded = [];
let seq = 0;

function record(req, bodyText) {
  seq += 1;
  recorded.push({
    seq,
    ts: new Date().toISOString(),
    method: req.method,
    path: req.url.split('?')[0],
    rawUrl: req.url,
    query: Object.fromEntries(new URL(req.url, 'http://mock').searchParams.entries()),
    headers: req.headers,
    body: bodyText,
    case: req.headers['x-f2b-case'] || null,
  });
}

/** Body for a route: either N filler bytes or {"probe":"<marker>"} / <marker>. */
function bodyFor(route) {
  if (!route) {
    return '';
  }
  if (route.bodyBytes) {
    return 'x'.repeat(route.bodyBytes);
  }
  const marker = route.bodyMarker || '';
  if (!marker) {
    return '';
  }
  const ct = (route.contentType || '').toLowerCase();
  return ct.includes('json') ? JSON.stringify({ probe: marker }) : marker;
}

function send(res, route, extraHeaders) {
  const headers = Object.assign(
    { 'content-type': route.contentType || 'application/json', 'x-f2b-mock': 'zorro-f2b' },
    route.extraHeaders || {},
    extraHeaders || {},
  );
  const body = bodyFor(route);
  res.writeHead(route.status || 200, headers);
  res.end(body);
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const bodyText = Buffer.concat(chunks).toString('utf8');
    record(req, bodyText);

    const url = new URL(req.url, 'http://mock');
    const routePath = url.pathname;

    if (routePath === '/__requests') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(recorded));
      return;
    }
    if (routePath === '/__reset') {
      recorded = [];
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"reset":true}');
      return;
    }
    if (routePath === '/__health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, recorded: recorded.length, routes: Object.keys(ROUTES) }));
      return;
    }

    /* Special route: destroy the socket — a network error the client cannot parse. */
    if (routePath === '/f2b/close') {
      req.socket.destroy();
      return;
    }

    /* Special route: 302 with Location pointing back at this very mock. */
    if (routePath === '/f2b/redirect') {
      const host = req.headers.host || 'localhost';
      const location = `http://${host}/f2b/ok`;
      res.writeHead(302, { location, 'content-type': 'text/plain' });
      res.end('');
      return;
    }

    /* Special route: slow answer — the delay comes from ?ms=. */
    if (routePath === '/f2b/slow') {
      const ms = parseInt(url.searchParams.get('ms') || '1000', 10);
      setTimeout(() => {
        try {
          send(res, ROUTES['/f2b/slow'] || { status: 200, contentType: 'application/json', bodyMarker: 'zorro-f2b' });
        } catch (e) {
          /* the client gave up (read timeout) — the socket is already gone, nothing to do */
        }
      }, ms);
      return;
    }

    const route = ROUTES[routePath];
    if (!route) {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'no such mock route', path: routePath }));
      return;
    }
    send(res, route);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[f2b-mock] listening on 0.0.0.0:${PORT}, routes: ${Object.keys(ROUTES).join(', ')}`);
  console.log(`[f2b-mock] case list: ${CASES}`);
});
