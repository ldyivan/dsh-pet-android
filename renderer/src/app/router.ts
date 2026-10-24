import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { RegisteredRoute } from '../standalone/context';
import { boundedRequest, MAX_REQUEST_BYTES, MAX_SETTINGS_BYTES } from '../standalone/request-body';
import { rejectRequest, type RequestSecurity } from '../standalone/request-security';
import { settingsHandler, testHandler, type StandaloneServerOptions } from '../standalone/server';
import { settingsPage, settingsScript, settingsStyle } from '../standalone/settings-page';

interface RouterDeps {
  security: RequestSecurity;
  ai?: StandaloneServerOptions['ai'];
  log(message: string): void;
  status(port: number): unknown;
  landingPage(port: number): string;
}

/** App HTTP boundary, isolated from Electron so malformed and untrusted requests can be tested. */
export function startRouter(
  routes: RegisteredRoute[],
  deps: RouterDeps,
): Promise<{ port: number; close: () => Promise<void> }> {
  const settings = settingsHandler(deps.ai);
  const tester = testHandler(deps.ai);
  let boundPort = 0;

  const server = createServer((req, res) => {
    let url: URL;
    try {
      url = new URL(req.url ?? '/', 'http://127.0.0.1');
    } catch {
      rejectRequest(res, 400, '请求地址无效');
      return;
    }
    const route = routes.find((candidate) =>
      candidate.kind === 'exact'
        ? url.pathname === candidate.path
        : url.pathname === candidate.path || url.pathname.startsWith(`${candidate.path}/`),
    );
    // Only plugin calls may come from a file renderer, and even those require its capability.
    if (!deps.security.authorize(req, res, !!route)) return;
    const method = req.method ?? 'GET';
    if (method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    const dispatch = async (request: IncomingMessage): Promise<void> => {
      const assets: Record<string, readonly [string, string]> = {
        '/standalone-ai/': [settingsPage, 'text/html'],
        '/standalone-ai/settings.js': [settingsScript, 'text/javascript'],
        '/standalone-ai/settings.css': [settingsStyle, 'text/css'],
      };
      const asset = assets[url.pathname];
      if (asset || url.pathname === '/health' || url.pathname === '/') {
        if (method !== 'GET' && method !== 'HEAD') {
          rejectRequest(res, 405, '仅支持 GET');
          return;
        }
        if (asset) sendText(res, 200, asset[1], asset[0]);
        else if (url.pathname === '/health')
          sendText(res, 200, 'application/json', JSON.stringify(deps.status(boundPort)));
        else sendText(res, 200, 'text/html', deps.landingPage(boundPort));
        return;
      }
      if (url.pathname === '/standalone-ai/settings' || url.pathname === '/standalone-ai/test') {
        await (url.pathname === '/standalone-ai/test' ? tester : settings)(request, res);
        return;
      }
      if (!route) {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('not found');
        return;
      }
      // CORS is useful to the authenticated file renderer; it grants no capability by itself.
      res.setHeader('access-control-allow-origin', '*');
      await route.handler(request, res);
    };

    const hasBody = req.headers['transfer-encoding'] !== undefined || Number(req.headers['content-length'] ?? 0) > 0;
    const bodyMethod = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method);
    void (async () => {
      if (hasBody || bodyMethod) {
        const limit = url.pathname === '/standalone-ai/settings' ? MAX_SETTINGS_BYTES : MAX_REQUEST_BYTES;
        const bounded = await boundedRequest(req, res, limit);
        if (bounded) await dispatch(bounded);
      } else await dispatch(req);
    })().catch(() => {
      // Request bodies, URLs and thrown values may contain credentials; keep logs generic.
      deps.log('本机接口处理失败');
      if (!res.headersSent) rejectRequest(res, 500, '接口暂时无法处理请求');
      else res.end();
    });
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  server.on('clientError', (_error, socket) => socket.destroy());

  return new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      boundPort = (server.address() as AddressInfo).port;
      resolvePromise({
        port: boundPort,
        close: async () => {
          server.closeAllConnections();
          await new Promise<void>((done) => server.close(() => done()));
        },
      });
    });
  });
}

function sendText(res: ServerResponse, status: number, type: string, body: string): void {
  res.writeHead(status, {
    'content-type': `${type}; charset=utf-8`,
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
  });
  res.end(body);
}
