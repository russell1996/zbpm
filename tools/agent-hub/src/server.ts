import express from 'express';
import { Store } from './store.js';
import { RateLimiter } from './rateLimiter.js';

const PORT = parseInt(process.env.PORT || '3456', 10);
const HOST = '127.0.0.1'; // ADR-9: only localhost, not externally reachable
const BUFFER_SIZE = parseInt(process.env.BUFFER_SIZE || '100', 10);

export function createApp(opts?: { stateFile?: string; bufferSize?: number }) {
  const store = new Store({
    bufferSize: opts?.bufferSize ?? BUFFER_SIZE,
    stateFile: opts?.stateFile,
  });
  const limiter = new RateLimiter(5, 60_000); // 5 per minute per agent
  const app = express();
  app.use(express.json());

  // POST /events — receive agent events
  app.post('/events', (req, res) => {
    const { agentId, status, action, detail } = req.body;
    if (!agentId || !status || !action) {
      return res.status(400).json({ error: 'agentId, status, action required' });
    }
    if (!['idle', 'working', 'waiting-permission', 'error'].includes(status)) {
      return res.status(400).json({ error: 'invalid status' });
    }
    const agent = store.handleEvent(agentId, status, action, detail);
    res.json({ ok: true, agent });
  });

  // GET /agents — list all agents with currentTask
  app.get('/agents', (_req, res) => {
    res.json(store.getAllAgents());
  });

  // GET /agents/:id/log — ring buffer
  app.get('/agents/:id/log', (req, res) => {
    const log = store.getLog(req.params.id);
    if (!log) return res.status(404).json({ error: 'agent not found' });
    res.json(log);
  });

  // POST /agents/:id/inbox — add incoming message (rate limited)
  app.post('/agents/:id/inbox', (req, res) => {
    const { from, text } = req.body;
    if (!text) return res.status(400).json({ error: 'text required' });
    const key = `inbox:${req.params.id}`;
    const { allowed, retryAfterMs } = limiter.check(key);
    if (!allowed) {
      return res.status(429).json({ error: 'rate limited', retryAfterMs });
    }
    const msg = store.addInbox(req.params.id, from || 'unknown', text);
    res.json({ ok: true, message: msg });
  });

  // GET /agents/:id/inbox — fetch and mark delivered (empty on repeat)
  app.get('/agents/:id/inbox', (req, res) => {
    const inbox = store.getInbox(req.params.id);
    if (inbox === null) return res.status(404).json({ error: 'agent not found' });
    res.json(inbox);
  });

  // For testing: expose store and limiter
  (app as any)._store = store;
  (app as any)._limiter = limiter;

  return { app, store, limiter };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { app } = createApp();
  const server = app.listen(PORT, HOST, () => {
    console.log(`Agent Hub listening on http://${HOST}:${PORT} (localhost only)`);
  });
  // Ensure server is not accessible externally — it binds to 127.0.0.1 only
  server.on('error', (err) => {
    console.error('Failed to start Agent Hub:', err);
    process.exit(1);
  });
}
