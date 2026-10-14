// intents.js - intents up, verdicts down: the busy trigger and its feedback
// Part of kroom-webapp-assets (after domhelper.js, api.js, sse.js)
//
// Usage:
//   <button data-intent="play" data-move="rock">Rock</button>
//   intents.attach({ room: 'chifoumi', sse: conn, store });
//
// A click posts {type: 'play', move: 'rock'} and leaves the button busy; the event itself comes
// back as a `play` frame, its metadata under `_`: APPLIED frees the button, REJECTED frees it and
// shows `_.reason` (+ `_.args`) right after it. Call sites hold no code. A frame is this tab's
// feedback when `_.tab` is its id — a door always stamps one; another tab's `play`, a room-born
// `play`, are just events.

const intents = (function() {
    // in flight in this tab, by intent name (a rerender may replace the element) → click token
    const pending = new Map();
    let tokens = 0;
    let attached = null;

    const quote = (s) => String(s).replace(/["\\]/g, '\\$&');
    const root = () => attached?.root || document;

    function datasetPayload(el) {
        const { intent, ...payload } = el.dataset;
        return payload;
    }

    function setBusy(el, busy) {
        if (busy) {
            el.setAttribute('aria-busy', 'true');
            el.setAttribute('disabled', '');
        } else {
            el.removeAttribute('aria-busy');
            el.removeAttribute('disabled');
        }
    }

    function busyTriggers(intent) {
        const selector = intent === undefined ? '[data-intent]' : `[data-intent="${quote(intent)}"]`;
        return [...root().querySelectorAll(`${selector}[aria-busy="true"]`)];
    }

    function clearReason(intent) {
        root().querySelectorAll(`[data-intent-reason="${quote(intent)}"]`).forEach(r => r.remove());
    }

    // a key plus its args, raw: the host may localize it
    function reasonText(reason, args) {
        const entries = Object.entries(args || {});
        return entries.length ? `${reason} (${entries.map(([k, v]) => `${k}: ${v}`).join(', ')})` : reason;
    }

    function showReason(intent, reason, triggers) {
        clearReason(intent);
        // the trigger may have been rerendered: fall back to any of that name
        const anchors = triggers.length ? triggers
            : [root().querySelector(`[data-intent="${quote(intent)}"]`)].filter(Boolean);
        anchors.forEach(el => {
            const small = document.createElement('small');
            small.className = 'intent-reason';
            small.setAttribute('role', 'alert');
            small.setAttribute('data-intent-reason', intent);
            small.textContent = reason;
            el.after(small);
        });
    }

    function free(intent) {
        pending.delete(intent);
        busyTriggers(intent).forEach(el => setBusy(el, false));
    }

    function freeAll() {
        pending.clear();
        busyTriggers().forEach(el => setBusy(el, false));
    }

    // One verdict per click, whichever arrives first: the frame or the HTTP failure.
    function settle(kind, verdict) {
        const intent = verdict?.intent;
        if (!pending.has(intent)) return;   // not ours, or already settled
        const triggers = busyTriggers(intent);
        free(intent);
        if (kind === 'rejected') showReason(intent, reasonText(verdict.reason || 'rejected', verdict.args), triggers);
        else clearReason(intent);
        attached?.store?.dispatch({ type: kind, payload: verdict });
    }

    // The frames of an intent name: ours when `_.tab` is this tab.
    function frame(intent) {
        return (data) => {
            if (!pending.has(intent)) return;
            let event;
            try { event = JSON.parse(data); } catch (err) { return console.error(`bad ${intent} frame:`, err); }
            const meta = event?._ || {};
            if (meta.tab !== api.tabId()) return;
            if (meta.stage === 'REJECTED' || meta.reason !== undefined) {
                settle('rejected', { intent, reason: meta.reason, args: meta.args || {} });
            } else {
                settle('accepted', { intent });
            }
        };
    }

    // sse.on() adds beside the host's handlers of the same name; ours are kept to be removed alone.
    function listen(intent) {
        const { sse, handlers } = attached;
        if (!sse || handlers.has(intent)) return;
        const handler = frame(intent);
        handlers.set(intent, handler);
        sse.on(intent, handler);
    }

    function onClick(event) {
        const el = event.target.closest?.('[data-intent]');
        if (!el || !root().contains(el)) return;
        // the intent replaces whatever the element would have done (submit, follow)
        event.preventDefault();
        const intent = el.dataset.intent;
        if (pending.has(intent)) return;
        listen(intent);     // a trigger rendered after attach
        const token = ++tokens;
        pending.set(intent, token);
        setBusy(el, true);
        clearReason(intent);
        const { act, payload } = attached;
        // the promise is a safety net only (transport down, refused before the loop)
        Promise.resolve()
            .then(() => act(intent, payload(el)))
            .catch(err => {
                if (pending.get(intent) === token) settle('rejected', { intent, reason: err?.message || String(err) });
            });
    }

    function attach({ room, act, sse, store, payload, root = document }) {
        detach();
        attached = {
            root, store, sse,
            handlers: new Map(),    // intent name → our frame handler
            act: act || ((type, body) => api.act(room, type, body)),
            payload: payload || datasetPayload
        };
        root.addEventListener('click', onClick);
        sse?.on('open', freeAll);
        root.querySelectorAll('[data-intent]').forEach(el => listen(el.dataset.intent));
    }

    function detach() {
        if (!attached) return;
        const { root, sse, handlers } = attached;
        root.removeEventListener('click', onClick);
        sse?.off('open', freeAll);
        handlers.forEach((handler, name) => sse?.off(name, handler));
        freeAll();
        attached = null;
    }

    return {
        attach,
        detach,
        busy: (intent) => pending.has(intent),
        free
    };
})();
