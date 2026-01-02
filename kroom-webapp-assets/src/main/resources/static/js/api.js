// api.js - fetch wrapper for REST APIs
// Part of kroom-webapp-assets

const api = (function() {
    const base = '/api/';

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

    return {
        get: (path, accept) => fetch(base + path, {
            credentials: "same-origin",
            headers: headers(accept, false)
        }),

        post: (path, body) => fetch(base + path, {
            credentials: "same-origin",
            method: 'POST',
            body: JSON.stringify(body),
            headers: headers()
        }),

        put: (path, body) => fetch(base + path, {
            credentials: "same-origin",
            method: 'PUT',
            body: JSON.stringify(body),
            headers: headers()
        }),

        delete: (path, body) => fetch(base + path, {
            credentials: "same-origin",
            method: 'DELETE',
            body: JSON.stringify(body),
            headers: headers()
        }),

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
        }
    };
})();
