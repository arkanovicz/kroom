// intents.js - intents up, verdicts back: the busy trigger and its feedback
// Part of kroom-webapp-assets (after domhelper.js, api.js)
//
// Usage:
//   <button data-intent="play" data-move="rock">Rock</button>
//   intents.attach({ room: 'chifoumi', store });
//
// A click posts {type: 'play', move: 'rock'} and leaves the button busy until the reply, which is
// the verdict: 200 frees it, 4xx frees it and shows `reason` (+ `args`) right after it. Call sites
// hold no code. The stream carries only events, the author's own as anyone's: the page handles them
// on one path.

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

    function settle(kind, verdict) {
        const intent = verdict?.intent;
        if (!pending.has(intent)) return;   // already settled
        const triggers = busyTriggers(intent);
        free(intent);
        if (kind === 'rejected') showReason(intent, reasonText(verdict.reason || 'rejected', verdict.args), triggers);
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
        // only the click still pending settles (free() or detach() may have dropped it)
        const ours = () => pending.get(intent) === token;
        Promise.resolve()
            .then(() => act(intent, payload(el)))
            .then(
                () => { if (ours()) settle('accepted', { intent }); },
                err => {
                    if (ours()) settle('rejected', { intent,
                        reason: err?.data?.reason ?? err?.message ?? String(err), args: err?.data?.args ?? {} });
                });
    }

    // `sse`, from callers of the frame era, is ignored
    function attach({ room, act, store, payload, root = document }) {
        detach();
        attached = {
            root, store,
            act: act || ((type, body) => api.act(room, type, body)),
            payload: payload || datasetPayload
        };
        root.addEventListener('click', onClick);
    }

    function detach() {
        if (!attached) return;
        attached.root.removeEventListener('click', onClick);
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
