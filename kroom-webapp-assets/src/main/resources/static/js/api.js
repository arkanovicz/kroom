// api.js - fetch wrapper for REST APIs
// Part of kroom-webapp-assets

const api = (function() {
    // API base: use window.kroomApiBase if set (for native apps with bundled assets)
    // Otherwise use relative '/api/' (works when served from same origin)
    const base = (window.kroomApiBase || '') + '/api/';

    function headers(accept, withJson) {
        let ret = {
            'Accept': `${accept || 'application/json'}; charset=utf-8`,
        };
        if (typeof withJson === 'undefined') withJson = true;
        if (withJson) {
            ret['Content-Type'] = 'application/json';
        }
        if (typeof apiToken !== 'undefined') {
            ret['Authorization'] = `Bearer ${apiToken}`;
        }
        return ret;
    }

    async function error(err) {
        if (err instanceof Response) {
            const contentType = err.headers.get('content-type') || '';
            let message = '';
            let data = null;
            try {
                if (contentType.includes('application/json')) {
                    data = await err.json();
                    message = data?.message ?? data?.error ?? JSON.stringify(data) ?? "";
                } else if (contentType.includes('text/plain')) {
                    message = await err.text();
                }
            } catch (_) {
                // ignore
            }
            message = message || err.statusText || 'Request failed';
            console.error(`${err.status} ${message}`);
            const apiError = new Error(message);
            apiError.status = err.status;
            apiError.data = data;
            throw apiError;
        } else {
            console.error(String(err));
            throw err;
        }
    }

    // Body-carrying request. FormData passes through untouched (the browser sets the
    // multipart Content-Type + boundary); anything else is sent as JSON. Either way the
    // Accept and Authorization headers are kept — only Content-Type is dropped for uploads.
    function send(method, path, body, extra) {
        const isForm = body instanceof FormData;
        return fetch(base + path, {
            credentials: "same-origin",
            method,
            body: isForm ? body : JSON.stringify(body),
            headers: Object.assign(isForm ? headers(undefined, false) : headers(), extra)
        });
    }

    // One id per tab: taken out of sessionStorage at load and put back on pagehide, so a reload
    // finds it again while a tab duplicated alive (its storage copied) finds none and makes its own.
    let tab = null;
    try {
        tab = sessionStorage.getItem('kroom.tabId');
        sessionStorage.removeItem('kroom.tabId');
    } catch (_) { /* storage blocked */ }
    if (!tab) {
        // randomUUID needs a secure context
        tab = window.crypto?.randomUUID?.()
            ?? Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
    }
    window.addEventListener('pagehide', () => {
        try { sessionStorage.setItem('kroom.tabId', tab); } catch (_) { /* this page's life only */ }
    });
    const tabId = () => tab;

    return {
        get: (path, accept) => fetch(base + path, {
            credentials: "same-origin",
            headers: headers(accept, false)
        }),

        post: (path, body) => send('POST', path, body),

        put: (path, body) => send('PUT', path, body),

        delete: (path, body) => send('DELETE', path, body),

        // Helpers returning parsed responses

        getHtml: (path) => {
            return api.get(path, 'text/html')
                .then(resp => resp.ok ? resp.text() : Promise.reject(resp))
                .catch(err => error(err));
        },

        getJson: (path) => {
            return api.get(path)
                .then(resp => resp.ok ? resp.json() : Promise.reject(resp))
                .catch(err => error(err));
        },

        postJson: (path, body) => {
            return api.post(path, body)
                .then(resp => resp.ok ? (resp.body ? resp.json() : Promise.resolve({})) : Promise.reject(resp))
                .catch(err => error(err));
        },

        putJson: (path, body) => {
            return api.put(path, body)
                .then(resp => resp.ok ? (resp.body ? resp.json() : Promise.resolve({})) : Promise.reject(resp))
                .catch(err => error(err));
        },

        deleteJson: (path, body) => {
            return api.delete(path, body)
                .then(resp => resp.ok ? (resp.body ? resp.json() : Promise.resolve({})) : Promise.reject(resp))
                .catch(err => error(err));
        },

        tabId,

        // where the API answers, for the URLs a caller builds itself (a room's stream)
        base,

        // The reply is the verdict: 200 accepted, 4xx rejects with `.data` = {reason, args}; the
        // effects come down the stream. `target` is a room name (the intent has its own path,
        // `rooms/<room>/<type>`, the payload its body), or a path of the app's own when it holds a slash
        // (the body carries the name as `type`).
        act: (target, type, payload) => {
            const own = target.includes('/');
            const path = own ? target : `rooms/${encodeURIComponent(target)}/${encodeURIComponent(type)}`;
            // on an app's own path the intent's name wins over a payload key: it keys the verdict
            return send('POST', path, own ? { ...payload, type } : payload, { 'X-Kroom-Tab': tabId() })
                .then(resp => resp.ok ? (resp.body ? resp.json() : Promise.resolve({})) : Promise.reject(resp))
                .catch(err => error(err));
        }
    };
})();
