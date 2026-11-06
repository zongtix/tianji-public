// Launch the existing Vue app in a dedicated loopback-only development instance.
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = path.resolve(process.argv[2]);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(path.join(frontend, 'package.json'));
const { createServer } = require('vite');
process.chdir(frontend);
process.env.VITE_MCP_PILOT = 'true';
process.env.VITE_API_BASE_URL = '/api';
process.env.VITE_API_PROXY_TARGET = 'http://127.0.0.1:10010';
const server = await createServer({
  root: frontend,
  mode: 'development',
  cacheDir: path.join(root, 'target/mcp-pilot/vite-cache'),
  logLevel: 'warn',
  server: { host: '127.0.0.1', port: 18084, strictPort: true, open: false },
});
await server.listen();
console.log('Course pilot: http://127.0.0.1:18084/#/assistant/index?pilot=mcp');
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await server.close(); process.exit(); });
