// Standalone entrypoint. superbot.js is built from Asset-3d-asset-2.5d/mcp/src/superbot.ts.
import http from 'node:http';
import {handleSuperBotRoute} from './superbot.js';
http.createServer(async(req,res)=>{
  const url=new URL(req.url,`http://${req.headers.host}`);
  const path=url.pathname.startsWith('/superbot/')?url.pathname:'/superbot'+url.pathname;
  try { if(await handleSuperBotRoute(req,res,path,url))return; }
  catch {res.writeHead(500);res.end(JSON.stringify({error:'request_failed'}));return;}
  res.writeHead(404);res.end();
}).listen(Number(process.env.PORT||10000),'0.0.0.0');
