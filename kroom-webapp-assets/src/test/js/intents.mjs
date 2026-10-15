// What intents.js, api.act and sse.js's stream parameters do, checked against a fake DOM, a fake server
// and a fake EventSource (sse.js needs one to load). Not part of `gradle test`: `./smoke.sh` here (node runs in docker).

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

/** The scripts loaded as a browser loads them (one shared scope), fetch and EventSource faked.
 *  `responder(url, body)` answers each fetch with {status, payload}, or a promise of it; a throw is a transport failure. */
function page(html, responder = () => ({})) {
    const dom = new JSDOM(`<!doctype html><html><body>${html}</body></html>`,
        { runScripts: 'outside-only', url: 'http://localhost/chifoumi' });
    const { window } = dom;
    const calls = [];
    const sources = [];
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        const body = typeof options.body === 'string' ? JSON.parse(options.body) : options.body;
        calls.push({ url, method: options.method || 'GET', headers: options.headers, body });
        const { status = 200, payload = {} } = (await responder(url, body)) || {};
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
    // per intent: a reply, or a pending one the test resolves
    const replies = {};
    const hold = (intent) => new Promise(resolve => { replies[intent] = { release: resolve }; });
    const { calls, click, $, window, api, intents } = page(buttons, (url, body) => {
        const reply = replies[body?.type];
        if (reply?.release) return new Promise(resolve => { reply.answer = resolve; });
        if (reply?.throws) throw new Error(reply.throws);
        return reply;
    });
    const dispatched = [];
    intents.attach({ room: 'chifoumi', store: { dispatch: (a) => dispatched.push(a) } });

    hold('play');
    click('[data-move="rock"] span');
    const rock = $('[data-move="rock"]');
    check('a click marks its trigger busy', [rock.getAttribute('aria-busy'), rock.disabled], ['true', true]);
    check('the intent is in flight', intents.busy('play'), true);
    await sleep(10);
    check('the intent is posted to the room', [calls.length, calls[0]?.url, calls[0]?.method], [1, '/api/rooms/chifoumi/act', 'POST']);
    check('its body is the type plus the other data-*', calls[0]?.body, { move: 'rock', type: 'play' });
    check('it names its tab', calls[0]?.headers['X-Kroom-Tab'], api.tabId());
    check('busy spans the post', [rock.getAttribute('aria-busy'), intents.busy('play')], ['true', true]);

    click('[data-move="paper"]');
    await sleep(10);
    check('a second click of a busy intent vanishes', [calls.length, $('[data-move="paper"]').hasAttribute('aria-busy')], [1, false]);

    replies.leave = { status: 200, payload: {} };
    click('[data-intent="leave"]');
    await sleep(10);
    check('another intent is not blocked', [calls.length, calls[1]?.body], [2, { type: 'leave' }]);
    dispatched.length = 0;

    replies.play.answer({ status: 200, payload: {} });
    await sleep(10);
    check('a 200 frees the trigger', [rock.hasAttribute('aria-busy'), rock.disabled, intents.busy('play')], [false, false, false]);
    check('the acceptance reaches the store', dispatched, [{ type: 'accepted', payload: { intent: 'play' } }]);
    check('and leaves no reason', $('[data-intent-reason="play"]'), null);

    replies.play = { status: 400, payload: { reason: 'not_your_turn', args: {} } };
    click('[data-move="paper"]');
    await sleep(10);
    const paper = $('[data-move="paper"]');
    const reason = paper.nextElementSibling;
    check('a 400 frees the trigger', [paper.hasAttribute('aria-busy'), paper.disabled, intents.busy('play')], [false, false, false]);
    check('and shows its reason right after it',
        [reason?.tagName, reason?.className, reason?.getAttribute('role'), reason?.dataset.intentReason, reason?.textContent],
        ['SMALL', 'intent-reason', 'alert', 'play', 'not_your_turn']);
    check('the rejection reaches the store', dispatched[1], { type: 'rejected', payload: { intent: 'play', reason: 'not_your_turn', args: {} } });

    replies.play = { status: 400, payload: { reason: 'missing', args: { field: 'move', max: 3 } } };
    click('[data-move="paper"]');
    await sleep(10);
    check('the args follow the reason',
        [window.document.querySelectorAll('.intent-reason').length, $('[data-intent-reason="play"]')?.textContent], [1, 'missing (field: move, max: 3)']);

    replies.play = { throws: 'network down' };
    click('[data-move="rock"]');
    check('the next click removes the reason', $('[data-intent-reason="play"]'), null);
    await sleep(10);
    check('a transport failure frees and shows its message',
        [intents.busy('play'), rock.nextElementSibling?.textContent], [false, 'network down']);

    hold('play');
    click('[data-move="rock"]');
    await sleep(10);
    intents.free('play');
    const before = dispatched.length;
    replies.play.answer({ status: 400, payload: { reason: 'late', args: {} } });
    await sleep(10);
    check('a reply to a click no longer pending settles nothing',
        [dispatched.length - before, $('[data-intent-reason="play"]')], [0, null]);

    intents.detach();
    const posted = calls.length;
    click('[data-move="rock"]');
    await sleep(10);
    check('detached, a click does nothing', [intents.busy('play'), calls.length], [false, posted]);
}

// --- the tab id: taken at load, put back on pagehide ----------------------------------------------
{
    const { window, api } = page('');
    const id = api.tabId();
    check('the id is taken out of sessionStorage at load', window.sessionStorage.getItem('kroom.tabId'), null);
    window.dispatchEvent(new window.Event('pagehide'));
    check('pagehide puts it back for a reload', window.sessionStorage.getItem('kroom.tabId'), id);
    const other = page('').api.tabId();
    check('another page makes its own', [typeof other, other.length > 0, other !== id], ['string', true, true]);
}

// --- a trigger rendered after attach --------------------------------------------------------------
{
    const { click, $, intents } = page('<div id="late"></div>');
    intents.attach({ room: 'chifoumi' });
    $('#late').innerHTML = '<button data-intent="fold">Fold</button>';
    click('[data-intent="fold"]');
    check('a late trigger goes busy on its click', intents.busy('fold'), true);
    await sleep(10);
    check('and its reply frees it', intents.busy('fold'), false);
    intents.detach();
}

// --- an HTTP error without a reason ---------------------------------------------------------------
{
    const { calls, click, $, intents } = page(buttons,
        (url) => url.includes('chifoumi') ? { status: 503, payload: { error: 'server restarting' } } : {});
    intents.attach({ room: 'chifoumi' });
    click('[data-move="rock"]');
    await sleep(20);
    const rock = $('[data-move="rock"]');
    check('a failed post frees the trigger', [rock.hasAttribute('aria-busy'), intents.busy('play')], [false, false]);
    check('and shows the error as its reason', rock.nextElementSibling?.textContent, 'server restarting');
    click('[data-move="rock"]');
    check('the next click clears it', $('.intent-reason'), null);
    check('and posts again', (await sleep(10), calls.length), 2);
}

// --- options: act, payload, root ------------------------------------------------------------------
{
    const { click, $, intents } = page(`<div id="a">${buttons}</div><button id="out" data-intent="play">x</button>`);
    const acted = [];
    intents.attach({ act: (type, payload) => { acted.push([type, payload]); return new Promise(() => {}); },
        payload: (el) => ({ move: el.dataset.move.toUpperCase() }), sse: {}, root: $('#a') });
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
    sources[0].fire('state', { round: 1 }, '7');
    sources[0].fire('played', { move: 'rock' }, '8');
    check('into() mirrors named events into the store', dispatched,
        [{ type: 'state', payload: { round: 1 } }, { type: 'played', payload: { move: 'rock' } }]);
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
