// intents.js - intents up, verdicts down: the busy trigger and its feedback
// Part of kroom-webapp-assets (after domhelper.js, api.js, sse.js)
//
// Usage:
//   <button data-intent="play" data-move="rock">Rock</button>
//   intents.attach({ room: 'chifoumi', sse: conn, store });
//
// A click posts {type: 'play', move: 'rock'} and leaves the button busy; the server's `accepted`
// or `rejected` frame frees it, a rejection showing its reason right after it. Call sites hold no code.

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
        if (kind === 'rejected') showReason(intent, verdict.reason || 'rejected', triggers);
        else clearReason(intent);
        attached?.store?.dispatch({ type: kind, payload: verdict });
    }

    function onClick(event) {
        const el = event.target.closest?.('[data-intent]');
        if (!el || !root().contains(el)) return;
        // the intent replaces whatever the element would have done (submit, follow)
        event.preventDefault();
        const intent = el.dataset.intent;
        if (pending.has(intent)) return;
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

    function frame(kind) {
        return (data) => {
            let verdict;
            try { verdict = JSON.parse(data); } catch (err) { return console.error(`bad ${kind} frame:`, err); }
            settle(kind, verdict);
        };
    }

    function attach({ room, act, sse, store, payload, root = document }) {
        detach();
        const handlers = { accepted: frame('accepted'), rejected: frame('rejected'), open: freeAll };
        attached = {
            root, store, sse, handlers,
            act: act || ((type, body) => api.act(room, type, body)),
            payload: payload || datasetPayload
        };
        root.addEventListener('click', onClick);
        Object.entries(handlers).forEach(([name, handler]) => sse?.on(name, handler));
    }

    function detach() {
        if (!attached) return;
        const { root, sse, handlers } = attached;
        root.removeEventListener('click', onClick);
        Object.entries(handlers).forEach(([name, handler]) => sse?.off(name, handler));
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
