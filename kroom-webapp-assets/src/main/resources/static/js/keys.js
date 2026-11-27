// keys.js - a page's keys, one owner each
// Part of kroom-webapp-assets (plain JS, no dependency; a Kotlin/JS bundle reads window.kroomKeys)
//
// Usage:
//   const off = kroomKeys.on('my-module', ['PageUp', 'PageDown'], element, event => { … });
//   kroomKeys.on('my-page', 'Escape', 'anywhere', close, { yields: true });
//   kroomKeys.list();   // who holds what, where
//
// A key is the browser's name for it (`event.key`; `Space` for ' '), after `Mod+` (Ctrl, or Cmd), `Alt+`,
// `Shift+` — Shift only before a letter or a named key: `?` is a key of its own. `Char` is any character typed
// (Shift or AltGr allowed, Space not): it collides with every key it covers. A context is where the focus is:
//   'idle'       nothing focused
//   'unfielded'  anything but a field typed in (an input, a textarea, a select, an editable)
//   'anywhere'
//   an element   the focus inside it
// or a list of them. Two claims of one key whose contexts can hold at once throw at registration, naming both
// owners — unless one is inside the other (an element inside an element, an element inside a page-wide context,
// 'idle' or 'unfielded' inside 'anywhere') and the outer one yields. A handler returning false declines: the key
// goes to the next claim out, which yields, and after the last to the browser. Anything else is handled (default
// prevented). An element that left the document claims nothing any more.

(function () {
    if (window.kroomKeys) return;

    const PAGE = ['idle', 'unfielded', 'anywhere'];
    const claims = [];

    /** `Mod+Shift+b` as one spelling, whatever order or case it was written in. */
    function canonical(spec) {
        if (spec === 'Char') return spec;
        const parts = spec.split('+');
        let key = parts.pop();
        if (key === '') key = '+';                                     // 'Mod++'
        if (key === ' ') key = 'Space';
        const mods = parts.map(m => ({ mod: 'Mod', ctrl: 'Mod', cmd: 'Mod', meta: 'Mod', alt: 'Alt', shift: 'Shift' })[m.toLowerCase()]
            ?? (() => { throw new Error(`kroomKeys: no modifier ${m} in ${spec}`); })());
        if (key.length === 1) key = key.toLowerCase();
        return spell(mods.includes('Mod'), mods.includes('Alt'), mods.includes('Shift'), key);
    }

    function spell(mod, alt, shift, key) {
        // a character other than a letter already says its shift
        if (key.length === 1 && key.toLowerCase() === key.toUpperCase()) shift = false;
        return (mod ? 'Mod+' : '') + (alt ? 'Alt+' : '') + (shift ? 'Shift+' : '') + key;
    }

    // AltGr types a character: on Windows it also says Ctrl and Alt
    const altGr = (event) => !!event.getModifierState?.('AltGraph');

    function spelled(event) {
        const key = event.key === ' ' ? 'Space' : event.key.length === 1 ? event.key.toLowerCase() : event.key;
        return altGr(event) ? spell(false, false, event.shiftKey, key) : spell(event.ctrlKey || event.metaKey, event.altKey, event.shiftKey, key);
    }

    const single = (key) => [...key].length === 1 && key !== ' ';

    /** Whether a key as spelled is a character typed, which `Char` covers. */
    const typed = (key) => key === 'Char' || single(key.replace(/^Shift\+(?=.)/, ''));

    /** Whether two keys as spelled meet: the same, or `Char` and a character. */
    const meet = (a, b) => a === b || a === 'Char' && typed(b) || b === 'Char' && typed(a);

    const TYPED = /^(text|search|email|url|tel|password|number|date|datetime-local|month|week|time)$/;
    function field(el) {
        if (!el) return false;
        const tag = el.tagName;
        return tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
            || tag === 'INPUT' && TYPED.test((el.getAttribute('type') || 'text').toLowerCase());
    }

    const focused = () => {
        const at = document.activeElement;
        return at && at !== document.body && at !== document.documentElement ? at : null;
    };

    /** How deep a context reaches when it holds for [at] (the focused element or null), -1 when it does not. */
    function depth(context, at) {
        switch (context) {
            case 'anywhere': return 0;
            case 'idle': return at ? -1 : 1;
            case 'unfielded': return field(at) ? -1 : 1;
        }
        if (!at || !context.contains(at)) return -1;
        let d = 2;
        for (let el = context; el.parentElement; el = el.parentElement) d++;
        return d;
    }

    /** Whether two contexts can hold at once; then which one is inside the other (1: b, -1: a, 0: neither, they are peers). */
    function overlap(a, b) {
        const page = c => PAGE.includes(c);
        if (page(a) && page(b)) {
            if (a === 'anywhere' && b === 'anywhere') return { holds: true, inner: 0 };
            if (a === 'anywhere') return { holds: true, inner: 1 };
            if (b === 'anywhere') return { holds: true, inner: -1 };
            return { holds: true, inner: 0 };                          // idle with idle or unfielded, unfielded with both
        }
        if (page(a) || page(b)) {
            const [p, el, sign] = page(a) ? [a, b, 1] : [b, a, -1];
            const holds = p === 'anywhere' || p === 'unfielded' && !field(el);
            return { holds, inner: sign };                             // the element is inside
        }
        if (a === b) return { holds: true, inner: 0 };
        if (a.contains(b)) return { holds: true, inner: 1 };
        if (b.contains(a)) return { holds: true, inner: -1 };
        return { holds: false };
    }

    function describe(context) {
        if (typeof context === 'string') return context;
        return context.tagName.toLowerCase() + (context.id ? '#' + context.id : '')
            + [...context.classList].map(c => '.' + c).join('');
    }

    /** A claim's contexts still in the page; none left, it is dropped. */
    function prune() {
        for (let i = claims.length - 1; i >= 0; i--) {
            const claim = claims[i];
            claim.contexts = claim.contexts.filter(c => typeof c === 'string' || c.isConnected);
            if (!claim.contexts.length) claims.splice(i, 1);
        }
    }

    function check(claim) {
        for (const other of claims) {
            if (!meet(other.key, claim.key)) continue;
            for (const a of claim.contexts) for (const b of other.contexts) {
                const { holds, inner } = overlap(a, b);
                if (!holds) continue;
                const outer = inner > 0 ? claim : inner < 0 ? other : null;   // inner > 0: b (the other's) is inside
                if (outer?.yields) continue;
                const why = outer ? `, and ${outer.owner}'s, the outer one, does not yield` : ', and neither is inside the other';
                throw new Error(`kroomKeys: ${claim.key} of ${claim.owner} (${describe(a)}) can hold with ${other.owner}'s${other.key === claim.key ? '' : ' ' + other.key} (${describe(b)})${why}`);
            }
        }
    }

    function on(owner, keys, context, handler, options = {}) {
        const contexts = [].concat(context);
        for (const c of contexts) if (typeof c === 'string' ? !PAGE.includes(c) : !(c instanceof Element))
            throw new Error(`kroomKeys: ${owner}'s context ${c} is neither ${PAGE.join(', ')} nor an element`);
        prune();
        const added = [].concat(keys).map(spec => ({ owner, key: canonical(spec), contexts, handler, yields: !!options.yields }));
        added.forEach(check);
        claims.push(...added);
        return () => added.forEach(claim => { const i = claims.indexOf(claim); if (i >= 0) claims.splice(i, 1); });
    }

    function list() {
        prune();
        return claims.map(c => ({ owner: c.owner, key: c.key, context: c.contexts.map(describe), yields: c.yields }));
    }

    // first of all listeners, so a key is its owner's whatever the page binds; not stopped: watchers still hear it
    window.addEventListener('keydown', event => {
        if (event.isComposing || event.defaultPrevented || !event.key) return;
        const key = spelled(event);
        const at = focused();
        const chain = claims
            .filter(c => c.key === key || c.key === 'Char' && typed(key))
            .map(c => ({ claim: c, depth: Math.max(...c.contexts.map(x => typeof x === 'string' || x.isConnected ? depth(x, at) : -1)) }))
            .filter(c => c.depth >= 0)
            .sort((a, b) => b.depth - a.depth);
        for (const { claim } of chain) {
            if (claim.handler(event) !== false) { event.preventDefault(); return; }
        }
    }, true);

    window.kroomKeys = { on, list };
})();
