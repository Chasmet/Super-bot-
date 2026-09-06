import http from 'node:http';
import { randomUUID } from 'node:crypto';

const PORT = Number(process.env.PORT || 10000);
const devices = new Map();
const queues = new Map();
const commands = new Map();

function json(res, status, body, extraHeaders = {}) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(data),
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'content-type,mcp-protocol-version,mcp-method,mcp-name',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
    ...extraHeaders,
  });
  res.end(data);
}

async function body(req) {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  if (!raw) return {};
  return JSON.parse(raw);
}

function getDevice(id = 'superbot-phone') {
  if (!devices.has(id)) {
    devices.set(id, {
      deviceId: id,
      online: false,
      lastSeen: 0,
      packageName: null,
      screenText: '',
      nodes: [],
      activeTask: null,
      lastResult: null,
    });
  }
  return devices.get(id);
}

function getQueue(id = 'superbot-phone') {
  if (!queues.has(id)) queues.set(id, []);
  return queues.get(id);
}

function enqueue(deviceId, type, payload = {}) {
  const cmd = {
    id: randomUUID(),
    deviceId,
    type,
    payload,
    status: 'queued',
    createdAt: Date.now(),
    result: null,
  };
  commands.set(cmd.id, cmd);
  getQueue(deviceId).push(cmd);
  return cmd;
}

function toolText(value) {
  return { content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] };
}

const tools = [
  {
    name: 'superbot_get_device_status',
    description: 'Retourne l’état de connexion du téléphone Super Bot et la dernière activité connue.',
    inputSchema: { type: 'object', properties: { deviceId: { type: 'string', default: 'superbot-phone' } } },
  },
  {
    name: 'superbot_get_screen_state',
    description: 'Lit l’écran courant vu par le service Accessibility de Super Bot: application, texte, nœuds et tâche active.',
    inputSchema: { type: 'object', properties: { deviceId: { type: 'string', default: 'superbot-phone' } } },
  },
  {
    name: 'superbot_click_text',
    description: 'Demande au téléphone de cliquer sur un texte visible précis, uniquement dans le flux social contrôlé par Super Bot.',
    inputSchema: { type: 'object', required: ['text'], properties: { deviceId: { type: 'string', default: 'superbot-phone' }, text: { type: 'string' } } },
  },
  {
    name: 'superbot_click_point',
    description: 'Demande un clic à une position écran précise x/y, uniquement dans le flux social Super Bot.',
    inputSchema: { type: 'object', required: ['x','y'], properties: { deviceId: { type: 'string', default: 'superbot-phone' }, x: { type: 'number' }, y: { type: 'number' } } },
  },
  {
    name: 'superbot_swipe',
    description: 'Demande un glissement tactile entre deux points sur le téléphone.',
    inputSchema: { type: 'object', required: ['x1','y1','x2','y2'], properties: { deviceId: { type: 'string', default: 'superbot-phone' }, x1: { type: 'number' }, y1: { type: 'number' }, x2: { type: 'number' }, y2: { type: 'number' }, durationMs: { type: 'integer', default: 350 } } },
  },
  {
    name: 'superbot_back',
    description: 'Demande au téléphone d’effectuer Retour Android dans le flux social actif.',
    inputSchema: { type: 'object', properties: { deviceId: { type: 'string', default: 'superbot-phone' } } },
  },
  {
    name: 'superbot_submit_publication',
    description: 'Envoie une mission de publication sociale à Super Bot Android avec plateforme, texte et date/heure cible.',
    inputSchema: {
      type: 'object',
      required: ['platform','scheduledAt'],
      properties: {
        deviceId: { type: 'string', default: 'superbot-phone' },
        platform: { type: 'string' },
        mediaUri: { type: 'string' },
        title: { type: 'string' },
        description: { type: 'string' },
        hashtags: { type: 'string' },
        scheduledAt: { type: 'integer', description: 'Epoch milliseconds' }
      }
    },
  },
  {
    name: 'superbot_get_task_status',
    description: 'Retourne l’état d’une commande ou mission envoyée au téléphone.',
    inputSchema: { type: 'object', required: ['commandId'], properties: { commandId: { type: 'string' } } },
  },
  {
    name: 'superbot_cancel_task',
    description: 'Demande l’annulation d’une mission Super Bot en cours.',
    inputSchema: { type: 'object', properties: { deviceId: { type: 'string', default: 'superbot-phone' }, commandId: { type: 'string' } } },
  }
];

function callTool(name, args = {}) {
  const deviceId = args.deviceId || 'superbot-phone';
  const d = getDevice(deviceId);
  if (name === 'superbot_get_device_status') {
    return toolText({
      deviceId,
      online: Date.now() - d.lastSeen < 15000,
      lastSeen: d.lastSeen,
      packageName: d.packageName,
      activeTask: d.activeTask,
      lastResult: d.lastResult,
    });
  }
  if (name === 'superbot_get_screen_state') {
    return toolText({
      deviceId,
      online: Date.now() - d.lastSeen < 15000,
      packageName: d.packageName,
      screenText: d.screenText,
      nodes: d.nodes,
      activeTask: d.activeTask,
    });
  }
  if (name === 'superbot_get_task_status') {
    const c = commands.get(args.commandId);
    return toolText(c || { error: 'command_not_found' });
  }
  if (name === 'superbot_cancel_task') {
    const c = enqueue(deviceId, 'cancel', { commandId: args.commandId || null });
    return toolText({ queued: true, commandId: c.id });
  }
  const mapping = {
    superbot_click_text: 'click_text',
    superbot_click_point: 'click_point',
    superbot_swipe: 'swipe',
    superbot_back: 'back',
    superbot_submit_publication: 'submit_publication',
  };
  if (mapping[name]) {
    const c = enqueue(deviceId, mapping[name], args);
    return toolText({ queued: true, commandId: c.id, deviceId });
  }
  return { isError: true, content: [{ type: 'text', text: `Outil inconnu: ${name}` }] };
}

function rpcResult(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function rpcError(id, code, message) {
  return { jsonrpc: '2.0', id: id ?? null, error: { code, message } };
}

async function handleMcp(req, res) {
  const msg = await body(req);
  const id = msg.id ?? null;
  const method = msg.method;

  if (method === 'initialize') {
    return json(res, 200, rpcResult(id, {
      protocolVersion: msg.params?.protocolVersion || '2025-11-25',
      capabilities: { tools: {} },
      serverInfo: { name: 'super-bot-mcp', version: '1.0.0' }
    }));
  }
  if (method === 'notifications/initialized') return json(res, 200, {});
  if (method === 'server/discover') {
    return json(res, 200, rpcResult(id, {
      protocolVersion: '2026-07-28',
      serverInfo: { name: 'super-bot-mcp', version: '1.0.0' },
      capabilities: { tools: {} }
    }));
  }
  if (method === 'tools/list') {
    return json(res, 200, rpcResult(id, { tools, ttlMs: 10000, cacheScope: 'private' }));
  }
  if (method === 'tools/call') {
    try {
      return json(res, 200, rpcResult(id, callTool(msg.params?.name, msg.params?.arguments || {})));
    } catch (e) {
      return json(res, 200, rpcResult(id, { isError: true, content: [{ type: 'text', text: String(e?.message || e) }] }));
    }
  }
  return json(res, 200, rpcError(id, -32601, 'Method not found'));
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'OPTIONS') return json(res, 200, { ok: true });
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === 'GET' && url.pathname === '/health') {
      return json(res, 200, { ok: true, service: 'super-bot-mcp', now: Date.now() });
    }

    if (url.pathname === '/mcp' && req.method === 'POST') {
      return await handleMcp(req, res);
    }

    if (url.pathname === '/device/register' && req.method === 'POST') {
      const b = await body(req);
      const d = getDevice(b.deviceId || 'superbot-phone');
      Object.assign(d, b, { online: true, lastSeen: Date.now() });
      return json(res, 200, { ok: true, deviceId: d.deviceId });
    }

    if (url.pathname === '/device/state' && req.method === 'POST') {
      const b = await body(req);
      const d = getDevice(b.deviceId || 'superbot-phone');
      Object.assign(d, b, { online: true, lastSeen: Date.now() });
      return json(res, 200, { ok: true });
    }

    if (url.pathname === '/device/commands' && req.method === 'GET') {
      const deviceId = url.searchParams.get('deviceId') || 'superbot-phone';
      const q = getQueue(deviceId);
      const out = q.splice(0, 8).map(c => {
        c.status = 'delivered';
        c.deliveredAt = Date.now();
        return c;
      });
      const d = getDevice(deviceId);
      d.lastSeen = Date.now();
      d.online = true;
      return json(res, 200, { commands: out });
    }

    const m = url.pathname.match(/^\/device\/commands\/([^/]+)\/result$/);
    if (m && req.method === 'POST') {
      const c = commands.get(m[1]);
      if (!c) return json(res, 404, { error: 'command_not_found' });
      const b = await body(req);
      c.status = b.ok === false ? 'failed' : 'completed';
      c.result = b;
      c.completedAt = Date.now();
      const d = getDevice(c.deviceId);
      d.lastSeen = Date.now();
      d.online = true;
      d.lastResult = { commandId: c.id, ...b };
      return json(res, 200, { ok: true });
    }

    return json(res, 404, { error: 'not_found' });
  } catch (e) {
    return json(res, 500, { error: String(e?.message || e) });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Super Bot MCP listening on ${PORT}`);
});
