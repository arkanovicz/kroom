// What intents.js, api.act and sse.js's stream parameters do, checked against a fake DOM, a fake server
// and a fake EventSource. Not part of `gradle test`: `./smoke.sh` here (node runs in docker).

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../main/resources/static/js';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** The scripts loaded as a browser loads them (one shared scope), fetch and EventSource faked. */
function page(html, responder = () => ({})) {
    const dom = new JSDOM(`<!doctype html><html><body>${html}</body></html>`,
        { runScripts: 'outside-only', url: 'http://localhost/chifoumi' });
    const { window } = dom;
    const calls = [];
    const sources = [];
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        calls.push({ url, method: options.method || 'GET', headers: options.headers,
            body: typeof options.body === 'string' ? JSON.parse(options.body) : options.body });
        const { status = 200, payload = {} } = responder(url) || {};
        return new Response(JSON.stringify(payload), { status, headers: { 'content-type': 'application/json' } });
    };
    window.EventSource = class {
        static CLOSED = 2;
        constructor(url) { this.url = url; this.readyState = 0; this.listeners = {}; sources.push(this); }
        addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
        close() { this.readyState = 2; }
        // a server frame
        fire(name, data, lastEventId = '') {
            const event = { data: JSON.stringify(data), lastEventId };
            if (name === 'message') this.onmessage(event);
            else (this.listeners[name] || []).forEach(fn => fn(event));
        }
        opened() { this.readyState = 1; this.onopen(); }
    };
    window.console.log = () => {};   // sse.js chatter
    window.eval(['domhelper.js', 'api.js', 'sse.js', 'intents.js']
        .map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
        .concat('window.__t = { api, sse, intents, createStore: typeof createStore === "undefined" ? null : createStore };')
        .join('\n;\n'));
    const $ = (s) => window.document.querySelector(s);
    const click = (selector) => $(selector)?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    return { window, calls, sources, click, $, ...window.__t };
}

const buttons = `
    <div id="moves">
      <button data-intent="play" data-move="rock"><span>Rock</span></button>
      <button data-intent="play" data-move="paper">Paper</button>
    </div>
    <button data-intent="leave">Leave</button>`;

// --- a click, its busy trigger and its post -------------------------------------------------------
{
    const { calls, sources, click, $, window, api, sse, intents } = page(buttons);
    const conn = sse('/events/chifoumi').connect();
    const dispatched = [];
    intents.attach({ room: 'chifoumi', sse: conn, store: { dispatch: (a) => dispatched.push(a) } });

    click('[data-move="rock"] span');
    const rock = $('[data-move="rock"]');
    check('a click marks its trigger busy', [rock.getAttribute('aria-busy'), rock.disabled], ['true', true]);
    check('the intent is in flight', intents.busy('play'), true);
    await sleep(10);
    check('the intent is posted to the room', [calls.length, calls[0]?.url, calls[0]?.method], [1, '/api/rooms/chifoumi/act', 'POST']);
    check('its body is the type plus the other data-*', calls[0]?.body, { move: 'rock', type: 'play' });
    check('it names its tab', calls[0]?.headers['X-Kroom-Tab'], api.tabId());
    check('the tab id is kept for the tab', window.sessionStorage.getItem('kroom.tabId'), api.tabId());

    click('[data-move="paper"]');
    await sleep(10);
    check('a second click of a busy intent vanishes', [calls.length, $('[data-move="paper"]').hasAttribute('aria-busy')], [1, false]);

    click('[data-intent="leave"]');
    await sleep(10);
    check('another intent is not blocked', [calls.length, calls[1]?.body], [2, { type: 'leave' }]);

    const es = sources[0];
    const hostSaw = [];
    conn.onJson('play', (e) => hostSaw.push(e));    // the host's own listener of the same event
    const applied = (tab, seq) => ({ _: { stage: 'APPLIED', from: 'alice', tab, seq }, move: 'rock' });

    es.fire('play', applied('another-tab', 41), '41');
    check("another tab's frame does not free the trigger", [rock.getAttribute('aria-busy'), intents.busy('play')], ['true', true]);
    es.fire('play', applied(api.tabId(), 42), '42');
    check('our applied frame frees the trigger', [rock.hasAttribute('aria-busy'), rock.disabled, intents.busy('play')], [false, false, false]);
    check('the other intent stays busy', intents.busy('leave'), true);
    check('the verdict reaches the store', dispatched, [{ type: 'accepted', payload: { intent: 'play' } }]);
    check("the host's listener sees every frame", hostSaw.map(e => e._.seq), [41, 42]);

    click('[data-move="paper"]');
    es.fire('play', { _: { stage: 'REJECTED', from: 'alice', tab: api.tabId(), reason: 'not_your_turn', args: {} } });
    const paper = $('[data-move="paper"]');
    const reason = paper.nextElementSibling;
    check('a rejected frame frees the trigger', [paper.hasAttribute('aria-busy'), paper.disabled, intents.busy('play')], [false, false, false]);
    check('and shows its reason right after it',
        [reason?.tagName, reason?.className, reason?.getAttribute('role'), reason?.dataset.intentReason, reason?.textContent],
        ['SMALL', 'intent-reason', 'alert', 'play', 'not_your_turn']);
    check('the rejection reaches the store', dispatched[1], { type: 'rejected', payload: { intent: 'play', reason: 'not_your_turn', args: {} } });

    es.fire('play', { _: { stage: 'REJECTED', tab: api.tabId(), reason: 'again', args: {} } });
    check('a frame with nothing in flight does nothing',
        [window.document.querySelectorAll('.intent-reason').length, $('.intent-reason').textContent, dispatched.length], [1, 'not_your_turn', 2]);

    click('[data-move="paper"]');
    es.fire('play', { _: { stage: 'REJECTED', reason: 'missing', args: { field: 'move', max: 3 } } });
    check('an untargeted frame (room-born) is not ours', intents.busy('play'), true);
    es.fire('play', { _: { stage: 'REJECTED', tab: api.tabId(), reason: 'missing', args: { field: 'move', max: 3 } } });
    check('the args follow the reason',
        [intents.busy('play'), $('[data-intent-reason="play"]')?.textContent], [false, 'missing (field: move, max: 3)']);

    click('[data-move="rock"]');
    check('the next click removes the reason', $('[data-intent-reason="play"]'), null);

    es.opened();
    check('a (re)opened stream frees everything in flight',
        [intents.busy('play'), intents.busy('leave'), window.document.querySelectorAll('[aria-busy]').length], [false, false, 0]);

    intents.detach();
    click('[data-move="rock"]');
    await sleep(10);
    check('detached, a click does nothing', [intents.busy('play'), calls.length], [false, 5]);
    es.fire('play', applied(api.tabId(), 43));
    check("detached, the host's listener stays", [hostSaw.length, hostSaw.at(-1)._.seq], [7, 43]);
}

// --- a trigger rendered after attach --------------------------------------------------------------
{
    const { sources, click, $, window, api, sse, intents } = page('<div id="late"></div>');
    const conn = sse('/events/chifoumi').connect();
    intents.attach({ room: 'chifoumi', sse: conn });
    $('#late').innerHTML = '<button data-intent="fold">Fold</button>';
    click('[data-intent="fold"]');
    await sleep(10);
    sources[0].fire('fold', { _: { stage: 'APPLIED', tab: api.tabId(), seq: 1 } });
    check('its name is listened to on its first click', intents.busy('fold'), false);
    intents.detach();
}

// --- the HTTP safety net --------------------------------------------------------------------------
{
    const { calls, sources, click, $, sse, intents } = page(buttons,
        (url) => url.includes('chifoumi') ? { status: 503, payload: { error: 'server restarting' } } : {});
    const conn = sse('/events/chifoumi').connect();
    intents.attach({ room: 'chifoumi', sse: conn });
    click('[data-move="rock"]');
    await sleep(20);
    const rock = $('[data-move="rock"]');
    check('a failed post frees the trigger', [rock.hasAttribute('aria-busy'), intents.busy('play')], [false, false]);
    check('and shows the error as its reason', rock.nextElementSibling?.textContent, 'server restarting');
    sources[0].fire('play', { _: { stage: 'REJECTED', reason: 'error', args: {} } });
    check('the frame that follows shows it once, not twice', document_reasons($), 1);
    click('[data-move="rock"]');
    check('the next click clears it', $('.intent-reason'), null);
    check('and posts again', (await sleep(10), calls.length), 2);
}

function document_reasons($) {
    return $('body').querySelectorAll('.intent-reason').length;
}

// --- options: act, payload, root ------------------------------------------------------------------
{
    const { click, $, sse, intents } = page(`<div id="a">${buttons}</div><button id="out" data-intent="play">x</button>`);
    const conn = sse('/events/chifoumi').connect();
    const acted = [];
    intents.attach({ act: (type, payload) => { acted.push([type, payload]); return Promise.resolve({}); },
        payload: (el) => ({ move: el.dataset.move.toUpperCase() }), sse: conn, root: $('#a') });
    click('#a [data-move="paper"]');
    await sleep(10);
    check('act and payload override the defaults', acted, [['play', { move: 'PAPER' }]]);
    intents.free('play');
    check('free() frees by name', [intents.busy('play'), $('[data-move="paper"]').hasAttribute('aria-busy')], [false, false]);
    click('#out');
    await sleep(10);
    check('a trigger outside the root is not ours', acted.length, 1);
    intents.detach();
}

// --- api.act -------------------------------------------------------------------------------------
{
    const { calls, api } = page('');
    await api.act('games/42/move', 'play', { move: 'rock', type: 'evil' });
    check('a path holding a slash is posted as is', calls[0].url, '/api/games/42/move');
    check('the intent name wins over a payload key', calls[0].body, { move: 'rock', type: 'play' });
    const id = api.tabId();
    check('one tab id per tab', [api.tabId() === id, id.length > 8], [true, true]);
}

// --- sse.js: tab id, into() ----------------------------------------------------------------------
{
    const { sources, sse, api, window } = page('');
    const dispatched = [];
    const conn = sse('/events/chifoumi?login=joe', { reconnectDelay: 5 })
        .into({ dispatch: (a) => dispatched.push(a) }, 'state', 'played')
        .connect();
    check('the stream names its tab', sources[0].url, `/events/chifoumi?login=joe&tabId=${api.tabId()}`);
    sources[0].fire('state', { _: { stage: 'APPLIED', seq: 7 }, round: 1 }, '7');
    sources[0].fire('played', { move: 'rock' }, '8');
    check('into() mirrors named events into the store, metadata untouched', dispatched,
        [{ type: 'state', payload: { _: { stage: 'APPLIED', seq: 7 }, round: 1 } }, { type: 'played', payload: { move: 'rock' } }]);
    sources[0].readyState = window.EventSource.CLOSED;
    sources[0].onerror(new window.Event('error'));
    await sleep(30);
    check('a manual retry asks for no replay', sources[1]?.url, `/events/chifoumi?login=joe&tabId=${api.tabId()}`);
    conn.disconnect();

    const bare = sse('/events/lobby').connect();
    check('no query yet, one is started', sources[2].url, `/events/lobby?tabId=${api.tabId()}`);
    bare.disconnect();
}

console.log(failures ? `\n${failures} FAILED` : '\nall green');
process.exit(failures ? 1 : 0);
