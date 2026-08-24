import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { getCurrentTask } from './branch.js';

export type AgentStatus = 'idle' | 'working' | 'waiting-permission' | 'error';

export interface AgentAction {
  timestamp: string;
  action: string;
  detail?: string;
}

export interface InboxMessage {
  id: string;
  from: string;
  text: string;
  timestamp: string;
  delivered: boolean;
}

export interface AgentState {
  id: string;
  status: AgentStatus;
  lastEventAt: string | null;
  buffer: AgentAction[];
  inbox: InboxMessage[];
}

export interface StoreData {
  agents: Record<string, AgentState>;
}

const DEFAULT_BUFFER_SIZE = 100;
const STATE_FILE = join(import.meta.dirname || '.', '..', 'state.json');
// Fallback for CJS: use process.cwd() if import.meta.dirname not available
function getStateFile(): string {
  try {
    // @ts-ignore
    if (typeof import.meta.dirname === 'string') return join(import.meta.dirname, '..', 'state.json');
  } catch {}
  return join(process.cwd(), 'state.json');
}

export class Store {
  private data: StoreData = { agents: {} };
  private bufferSize: number;
  private stateFile: string;

  constructor(opts?: { bufferSize?: number; stateFile?: string }) {
    this.bufferSize = opts?.bufferSize ?? DEFAULT_BUFFER_SIZE;
    this.stateFile = opts?.stateFile ?? getStateFile();
    this.load();
  }

  private load() {
    try {
      if (existsSync(this.stateFile)) {
        const raw = readFileSync(this.stateFile, 'utf8');
        const parsed = JSON.parse(raw) as StoreData;
        if (parsed && typeof parsed.agents === 'object') {
          this.data = parsed;
        }
      }
    } catch {
      // ignore corrupt file, start fresh
      this.data = { agents: {} };
    }
  }

  private save() {
    try {
      mkdirSync(dirname(this.stateFile), { recursive: true });
      writeFileSync(this.stateFile, JSON.stringify(this.data, null, 2), 'utf8');
    } catch {
      // ignore write errors (e.g., read-only)
    }
  }

  private ensureAgent(id: string): AgentState {
    if (!this.data.agents[id]) {
      this.data.agents[id] = {
        id,
        status: 'idle',
        lastEventAt: null,
        buffer: [],
        inbox: [],
      };
    }
    return this.data.agents[id];
  }

  handleEvent(agentId: string, status: AgentStatus, action: string, detail?: string) {
    const agent = this.ensureAgent(agentId);
    agent.status = status;
    agent.lastEventAt = new Date().toISOString();
    agent.buffer.push({
      timestamp: agent.lastEventAt,
      action,
      detail,
    });
    // Ring buffer: keep only last N
    if (agent.buffer.length > this.bufferSize) {
      agent.buffer = agent.buffer.slice(-this.bufferSize);
    }
    this.save();
    return agent;
  }

  getAgent(id: string): (AgentState & { currentTask: string | null }) | null {
    const agent = this.data.agents[id];
    if (!agent) return null;
    return {
      ...agent,
      currentTask: getCurrentTask(),
    };
  }

  getAllAgents(): Array<AgentState & { currentTask: string | null }> {
    const task = getCurrentTask();
    return Object.values(this.data.agents).map((a) => ({
      ...a,
      currentTask: task,
    }));
  }

  getLog(agentId: string): AgentAction[] | null {
    const agent = this.data.agents[agentId];
    if (!agent) return null;
    return [...agent.buffer];
  }

  addInbox(agentId: string, from: string, text: string): InboxMessage {
    const agent = this.ensureAgent(agentId);
    const msg: InboxMessage = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      from,
      text,
      timestamp: new Date().toISOString(),
      delivered: false,
    };
    agent.inbox.push(msg);
    this.save();
    return msg;
  }

  getInbox(agentId: string): InboxMessage[] | null {
    const agent = this.data.agents[agentId];
    if (!agent) return null;
    // Return copy and mark as delivered, then clear delivered ones on next fetch?
    // Spec: "GET /agents/{id}/inbox (забрать и пометить доставленным)" — so GET should return and clear.
    // We implement: GET returns all undelivered, marks them delivered, and then clears the inbox for next GET to be empty.
    // Actually spec says "забрать и пометить доставленным" — so after GET, the messages are considered delivered and should not be returned again.
    // We implement as: return copy of current inbox, then clear it.
    const copy = [...agent.inbox];
    // Mark as delivered (for history, we could keep but spec says empty on repeat)
    // For criterion 6: "Входящее забирается один раз (повторный запрос пуст)" — so second GET should be empty.
    agent.inbox = [];
    this.save();
    return copy;
  }

  clear() {
    this.data = { agents: {} };
    this.save();
  }

  // For testing: set buffer size
  setBufferSize(n: number) {
    this.bufferSize = n;
  }
}
