import { pathToFileURL } from 'node:url';

interface RequestDetails {
  url: string;
  requestHeaders: Record<string, string>;
  frame?: { url: string; parent: unknown } | null;
}

export interface DesktopSession {
  webRequest: {
    onBeforeSendHeaders(
      listener: (
        details: RequestDetails,
        callback: (response: { requestHeaders: Record<string, string> }) => void,
      ) => void,
    ): void;
  };
}

/** Keep the capability in the main process, scoped to our local top-level renderer and API. */
export function installDesktopAuth(session: DesktopSession, origin: string, rendererPath: string, token: string): void {
  const renderer = pathToFileURL(rendererPath).href;
  session.webRequest.onBeforeSendHeaders((details, callback) => {
    const headers = { ...details.requestHeaders };
    // A redirected request must not carry the capability to another destination.
    for (const name of Object.keys(headers)) {
      if (name.toLowerCase() === 'x-dsh-pet-token') delete headers[name];
    }
    try {
      const target = new URL(details.url);
      const frame = details.frame;
      const page = frame ? new URL(frame.url) : undefined;
      if (
        target.origin === origin &&
        target.pathname.startsWith('/dsh-pet-7340/') &&
        frame?.parent === null &&
        page?.protocol === 'file:' &&
        page.origin === 'null' &&
        page.href.split(/[?#]/, 1)[0] === renderer
      ) {
        headers['X-DSH-Pet-Token'] = token;
      }
    } catch {
      // Missing/destroyed frames and invalid URLs never receive a capability.
    }
    callback({ requestHeaders: headers });
  });
}
