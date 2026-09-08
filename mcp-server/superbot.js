import { randomUUID } from "node:crypto";
const devices = new Map();
const queues = new Map();
const commands = new Map();
function writeJson(res, status, payload) {
    const body = JSON.stringify(payload);
    res.writeHead(status, {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
        "access-control-allow-origin": "*",
        "access-control-allow-headers": "content-type,mcp-protocol-version",
        "access-control-allow-methods": "GET,POST,OPTIONS",
        "content-length": Buffer.byteLength(body),
    });
    res.end(body);
}
async function readJson(req) {
    let raw = "";
    for await (const chunk of req) {
        raw += String(chunk);
        if (raw.length > 2_000_000)
            throw new Error("request_too_large");
    }
    return raw ? JSON.parse(raw) : {};
}
function device(id = "superbot-phone") {
    if (!devices.has(id)) {
        devices.set(id, {
            deviceId: id,
            online: false,
            lastSeen: 0,
            packageName: null,
            screenText: "",
            nodes: [],
            activeTask: null,
            lastResult: null,
        });
    }
    return devices.get(id);
}
function queue(id = "superbot-phone") {
    if (!queues.has(id))
        queues.set(id, []);
    return queues.get(id);
}
function enqueue(deviceId, type, payload = {}) {
    const command = {
        id: randomUUID(),
        deviceId,
        type,
        payload,
        status: "queued",
        createdAt: Date.now(),
    };
    commands.set(command.id, command);
    queue(deviceId).push(command);
    return command;
}
function textResult(value) {
    return {
        content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value) }],
    };
}
const tools = [
    {
        name: "superbot_get_device_status",
        description: "Retourne l'état de connexion du téléphone Super Bot et la dernière activité connue.",
        inputSchema: { type: "object", properties: { deviceId: { type: "string", default: "superbot-phone" } } },
    },
    {
        name: "superbot_get_screen_state",
        description: "Lit l'écran courant transmis par Super Bot Android: application, texte, nœuds et tâche active.",
        inputSchema: { type: "object", properties: { deviceId: { type: "string", default: "superbot-phone" } } },
    },
    {
        name: "superbot_click_text",
        description: "Demande au téléphone de cliquer sur un texte visible dans le flux social Super Bot.",
        inputSchema: { type: "object", required: ["text"], properties: { deviceId: { type: "string", default: "superbot-phone" }, text: { type: "string" } } },
    },
    {
        name: "superbot_click_point",
        description: "Demande un clic à une position écran précise x/y dans le flux social Super Bot.",
        inputSchema: { type: "object", required: ["x", "y"], properties: { deviceId: { type: "string", default: "superbot-phone" }, x: { type: "number" }, y: { type: "number" } } },
    },
    {
        name: "superbot_swipe",
        description: "Demande un glissement tactile entre deux points sur le téléphone.",
        inputSchema: { type: "object", required: ["x1", "y1", "x2", "y2"], properties: { deviceId: { type: "string", default: "superbot-phone" }, x1: { type: "number" }, y1: { type: "number" }, x2: { type: "number" }, y2: { type: "number" }, durationMs: { type: "integer", default: 350 } } },
    },
    {
        name: "superbot_back",
        description: "Demande au téléphone d'effectuer Retour Android dans le flux social actif.",
        inputSchema: { type: "object", properties: { deviceId: { type: "string", default: "superbot-phone" } } },
    },
    {
        name: "superbot_submit_publication",
        description: "Programme une vidéo. Une seule mission active par téléphone. queued/delivered/running ne sont pas une réussite. Attendre completed avec confirmation TikTok et retour au menu avant la suivante.",
        inputSchema: {
            type: "object",
            required: ["platform", "scheduledAt"],
            properties: {
                deviceId: { type: "string", default: "superbot-phone" },
                platform: { type: "string" },
                mediaUri: { type: "string" },
                title: { type: "string" },
                description: { type: "string" },
                hashtags: { type: "string" },
                scheduledAt: { type: "integer" },
            },
        },
    },
    {
        name: "superbot_get_task_status",
        description: "Retourne l'état d'une commande envoyée au téléphone.",
        inputSchema: { type: "object", required: ["commandId"], properties: { commandId: { type: "string" } } },
    },
    {
        name: "superbot_cancel_task",
        description: "Demande l'annulation d'une mission Super Bot en cours.",
        inputSchema: { type: "object", properties: { deviceId: { type: "string", default: "superbot-phone" }, commandId: { type: "string" } } },
    },
];
function takeCommands(deviceId) {
    const pending = queue(deviceId), out = [];
    let busy = [...commands.values()].some(c => c.deviceId === deviceId && c.type === "submit_publication" && !["queued", "completed"].includes(c.status));
    // An old/local Android mission is also a lock even if the server restarted.
    if (device(deviceId).activeTask)
        busy = true;
    for (let i = 0; i < pending.length && out.length < 8;) {
        const c = pending[i];
        if (c.type === "submit_publication" && busy) {
            i++;
            continue;
        }
        if (c.type === "submit_publication")
            busy = true;
        pending.splice(i, 1);
        c.status = "delivered";
        c.deliveredAt = Date.now();
        out.push(c);
    }
    return out;
}
function applyResult(c, b) {
    if (["completed", "failed", "cancelled"].includes(c.status))
        return;
    if (c.type !== "submit_publication")
        c.status = b.ok === false ? "failed" : "completed";
    else if (b.ok === false)
        c.status = b.status === "cancelled" ? "cancelled" : "failed";
    else if (b.status === "completed" && b.taskId === c.id && b.confirmation === "tiktok_schedule_confirmed" && b.menuReturned === true)
        c.status = "completed";
    else
        c.status = b.status === "paused" ? "paused" : "running";
    c.result = b;
    if (["completed", "failed", "cancelled"].includes(c.status))
        c.completedAt = Date.now();
}
function callTool(name, args = {}) {
    const deviceId = String(args.deviceId || "superbot-phone");
    const current = device(deviceId);
    if (name === "superbot_get_device_status") {
        return textResult({
            deviceId,
            online: Date.now() - current.lastSeen < 15_000,
            lastSeen: current.lastSeen,
            packageName: current.packageName,
            protocolVersion: 2,
            activeTask: current.activeTask,
            lastResult: current.lastResult,
        });
    }
    if (name === "superbot_get_screen_state") {
        return textResult({
            deviceId,
            online: Date.now() - current.lastSeen < 15_000,
            packageName: current.packageName,
            screenText: current.screenText,
            nodes: current.nodes,
            protocolVersion: 2,
            activeTask: current.activeTask,
        });
    }
    if (name === "superbot_get_task_status")
        return textResult(commands.get(String(args.commandId)) || { error: "command_not_found" });
    if (name === "superbot_cancel_task") {
        const c = enqueue(deviceId, "cancel", { commandId: args.commandId || null });
        return textResult({ queued: true, commandId: c.id, deviceId });
    }
    const mapping = {
        superbot_click_text: "click_text",
        superbot_click_point: "click_point",
        superbot_swipe: "swipe",
        superbot_back: "back",
        superbot_submit_publication: "submit_publication",
    };
    const type = mapping[name];
    if (!type)
        return { isError: true, content: [{ type: "text", text: `Outil inconnu: ${name}` }] };
    if (type === "submit_publication") {
        if (!Number.isSafeInteger(args.scheduledAt) || args.scheduledAt <= Date.now() + 60000)
            return { isError: true, ...textResult({ error: "scheduled_time_expired_or_invalid", unit: "epoch_milliseconds" }) };
        if (!String(args.mediaUri || "").trim())
            return { isError: true, ...textResult({ error: "mediaUri_required" }) };
    }
    const c = enqueue(deviceId, type, args);
    return textResult({ queued: true, commandId: c.id, deviceId });
}
function rpcResult(id, result) {
    return { jsonrpc: "2.0", id, result };
}
function rpcError(id, code, message) {
    return { jsonrpc: "2.0", id: id ?? null, error: { code, message } };
}
async function handleMcp(req, res) {
    const msg = await readJson(req);
    const id = msg.id ?? null;
    if (msg.method === "initialize") {
        writeJson(res, 200, rpcResult(id, {
            protocolVersion: msg.params?.protocolVersion || "2025-11-25",
            capabilities: { tools: {} },
            serverInfo: { name: "super-bot-mcp", version: "1.1.0" },
        }));
        return;
    }
    if (msg.method === "notifications/initialized") {
        writeJson(res, 200, {});
        return;
    }
    if (msg.method === "tools/list") {
        writeJson(res, 200, rpcResult(id, { tools }));
        return;
    }
    if (msg.method === "tools/call") {
        try {
            writeJson(res, 200, rpcResult(id, callTool(String(msg.params?.name || ""), msg.params?.arguments || {})));
        }
        catch (error) {
            writeJson(res, 200, rpcResult(id, { isError: true, content: [{ type: "text", text: String(error instanceof Error ? error.message : error) }] }));
        }
        return;
    }
    writeJson(res, 200, rpcError(id, -32601, "Method not found"));
}
export async function handleSuperBotRoute(req, res, pathname, url) {
    if (!pathname.startsWith("/superbot/"))
        return false;
    if (req.method === "OPTIONS") {
        writeJson(res, 200, { ok: true });
        return true;
    }
    if (req.method === "GET" && pathname === "/superbot/health") {
        writeJson(res, 200, { ok: true, service: "super-bot-mcp", protocolVersion: 2, version: "1.1.0", mcpPath: "/superbot/mcp", now: Date.now() });
        return true;
    }
    if (req.method === "POST" && pathname === "/superbot/mcp") {
        await handleMcp(req, res);
        return true;
    }
    if (req.method === "POST" && pathname === "/superbot/device/register") {
        const b = await readJson(req);
        const d = device(String(b.deviceId || "superbot-phone"));
        Object.assign(d, b, { online: true, lastSeen: Date.now() });
        writeJson(res, 200, { ok: true, deviceId: d.deviceId });
        return true;
    }
    if (req.method === "POST" && pathname === "/superbot/device/state") {
        const b = await readJson(req);
        const d = device(String(b.deviceId || "superbot-phone"));
        Object.assign(d, b, { online: true, lastSeen: Date.now() });
        writeJson(res, 200, { ok: true });
        return true;
    }
    if (req.method === "GET" && pathname === "/superbot/device/commands") {
        const deviceId = url.searchParams.get("deviceId") || "superbot-phone";
        const out = takeCommands(deviceId);
        const d = device(deviceId);
        d.lastSeen = Date.now();
        d.online = true;
        writeJson(res, 200, { commands: out });
        return true;
    }
    const match = pathname.match(/^\/superbot\/device\/commands\/([^/]+)\/(result|progress)$/);
    if (match && req.method === "POST") {
        const c = commands.get(match[1]);
        if (!c) {
            writeJson(res, 404, { error: "command_not_found" });
            return true;
        }
        const b = await readJson(req);
        if (match[2] === "progress") {
            if (!["completed", "failed", "cancelled"].includes(c.status)) {
                c.status = b.status === "paused" ? "paused" : "running";
                c.result = b;
            }
        }
        else
            applyResult(c, b);
        const d = device(c.deviceId);
        d.lastSeen = Date.now();
        d.online = true;
        d.lastResult = { commandId: c.id, ...b };
        writeJson(res, 200, { ok: true });
        return true;
    }
    writeJson(res, 404, { error: "not_found" });
    return true;
}
