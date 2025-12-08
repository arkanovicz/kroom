// api.js - fetch wrapper for REST APIs
// Part of kroom-webapp-assets

const apiVersion = '1.0';

// Usage:
// api.put('user', {user_id: 12, first_name: 'Toto', ... })
//     .then(ret => ret.json())
//     .then(json => { ... })
//     .catch(err => { ... });

const api = (function() {
    const base = '/api/';

    function headers(accept, withJson) {
        let ret = {
            'Accept': `${accept || 'application/json'}; charset=utf-8`,
        };
        if (typeof(withJson) === 'undefined') withJson = true;
        if (withJson) {
            ret['Content-Type'] = 'application/json';
        }
        if (typeof(apiToken) !== 'undefined') {
            ret['Authorization'] = `Bearer ${apiToken}`;
        }
        return ret;
    }

    async function error(err) {
        if (err instanceof Response) {
            const contentType = err.headers.get('content-type') || '';
            let message = '';
            try {
                if (contentType.includes('application/json')) {
                    const json = await err.json();
                    message = json?.message ?? JSON.stringify(json) ?? "";
                } else if (contentType.includes('text/plain')) {
                    message = await err.text();
                }
            } catch (_) {
                // ignore
            }
            message = message || err.statusText || 'Request failed';
            console.error(`${err.status} ${message}`);
            throw new Error(message);
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

        /* Helpers returning parsed responses */

        getHtml: (path) => {
            return api.get(path, 'text/html')
                .then(resp => {
                    if (resp.ok) {
                        return resp.text();
                    }
                    else throw resp;
                }).catch(err => error(err));
        },

        getJson: (path) => {
            return api.get(path)
                .then(resp => {
                    if (resp.ok) {
                        return resp.json();
                    }
                    else throw resp;
                }).catch(err => error(err));
        },

        postJson: (path, body) => {
            return api.post(path, body)
                .then(resp => {
                    if (resp.ok) {
                        return resp.body ? resp.json() : Promise.resolve({});
                    }
                    else {
                        throw resp;
                    }
                }).catch(err => error(err));
        },

        putJson: (path, body) => {
            return api.put(path, body)
                .then(resp => {
                    if (resp.ok) {
                        return resp.body ? resp.json() : Promise.resolve({});
                    }
                    else throw resp;
                }).catch(err => error(err));
        },

        deleteJson: (path, body) => {
            return api.delete(path, body)
                .then(resp => {
                    if (resp.ok) {
                        return resp.body ? resp.json() : Promise.resolve({});
                    }
                    else throw resp;
                }).catch(err => error(err));
        }
    };
})();
