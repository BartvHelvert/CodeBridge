// The service worker of a webview origin, as VS Code's webview host has one (its `service-worker.js`):
// the files a page reads by `asWebviewUri` are kept in the browser's own cache, by the version the
// backend gave them, and asked for again only with that version, which the backend answers without
// the file while it has not changed. A page loaded again then reads its scripts as the browser
// keeps them, compiled ones included, rather than from the backend.
//
// The workbench is asked at this origin's own `/vscode-resource`, naming the page that wants the file:
// the backend allows a page only what its extension let it read.
'use strict';

const CACHE = 'codebridge-resources-1';
const RESOURCES = '.vscode-resource.vscode-cdn.net';

self.addEventListener('install', event => event.waitUntil(self.skipWaiting()));
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', event => {
    const request = event.request;
    const url = new URL(request.url);
    if (url.origin === self.location.origin) {
        // A page of this origin: from the workbench, told it comes through here.
        if (request.mode === 'navigate') {
            url.searchParams.set('sw', '1');
            event.respondWith(fetch(url.href));
        }
        return;
    }
    if (!url.hostname.endsWith(RESOURCES)) return;
    if ((request.method !== 'GET' && request.method !== 'HEAD') || request.headers.has('range')) return;
    event.respondWith(resource(event));
});

/** The page [event] came from: its handle, which the workbench put in its address. */
async function handleOf(event) {
    const client = await self.clients.get(event.clientId);
    const handle = client && new URL(client.url).searchParams.get('handle');
    if (handle) return handle;
    // A worker of a page names no handle of its own; any page of this origin may read what its
    // extension's pages may.
    for (const window of await self.clients.matchAll({type: 'window'})) {
        const its = new URL(window.url).searchParams.get('handle');
        if (its) return its;
    }
    return null;
}

const notFound = () => new Response('Not Found', {status: 404, headers: {'Access-Control-Allow-Origin': '*'}});

/** Tells the workbench what went wrong: nobody sees a service worker's console. */
function warn(message) {
    const url = new URL('/service-worker-log', self.location.origin);
    url.searchParams.set('message', String(message));
    return fetch(url.href).catch(() => {});
}

/** The browser's cache of this origin's files, or null where it cannot be had: they are then read every time. */
async function openCache() {
    try {
        return await caches.open(CACHE);
    } catch (error) {
        warn('The cache could not be opened: ' + error);
        return null;
    }
}

async function resource(event) {
    try {
        return await fromWorkbench(event);
    } catch (error) {
        warn('Could not serve ' + event.request.url + ': ' + (error && error.stack || error));
        return notFound();
    }
}

async function fromWorkbench(event) {
    const handle = await handleOf(event);
    if (!handle) {
        warn('No page of this origin asked for ' + event.request.url);
        return notFound();
    }
    const cache = await openCache();
    const cached = cache && await cache.match(event.request.url).catch(() => undefined);
    const version = cached && cached.headers.get('ETag');

    const ask = new URL('/vscode-resource', self.location.origin);
    ask.searchParams.set('handle', handle);
    ask.searchParams.set('url', event.request.url);
    if (version) ask.searchParams.set('version', version);
    const answer = await fetch(ask.href);
    if (answer.status === 304 && cached) return cached;
    if (answer.status !== 200) return notFound();

    const headers = new Headers({
        'Content-Type': answer.headers.get('Content-Type') || 'application/octet-stream',
        'Access-Control-Allow-Origin': '*',
        'Cross-Origin-Resource-Policy': 'cross-origin',
        'Cache-Control': 'no-cache',
    });
    const tag = answer.headers.get('ETag');
    if (tag) headers.set('ETag', tag);
    const response = new Response(answer.body, {status: 200, headers});
    if (cache && tag && event.request.method === 'GET') {
        event.waitUntil(cache.put(event.request.url, response.clone()).catch(error => warn('Could not keep ' + event.request.url + ': ' + error)));
    }
    return response;
}
