const IPV4 = /^\d{1,3}(\.\d{1,3}){3}(:\d+)?$/;
const LOCAL = /^(localhost|127\.0\.0\.1)(:\d+)?$/i;

/**
 * Turns what someone pastes into the server's base URL: a bare host (`3-91-20-7.sslip.io`), the landing page, or a
 * health URL all become `https://3-91-20-7.sslip.io`. Bare IP addresses and localhost get http://, since they have no
 * certificate. Returns undefined for anything that is not an http(s) address.
 */
export function normalizeServerUrl(input: string): string | undefined {
  let text = input.trim();
  if (!text) {
    return undefined;
  }
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) {
    const host = text.split('/')[0];
    text = `${IPV4.test(host) || LOCAL.test(host) ? 'http' : 'https'}://${text}`;
  }
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return undefined;
  }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || !url.hostname) {
    return undefined;
  }
  // The client appends /api/v1 itself, so drop anything from /api on and any trailing slash.
  const path = url.pathname.replace(/\/api(\/.*)?$/, '').replace(/\/+$/, '');
  return `${url.protocol}//${url.host}${path}`;
}
