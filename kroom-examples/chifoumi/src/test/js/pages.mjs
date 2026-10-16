// The chifoumi pages in jsdom, driven by the frames a JVM test recorded (LobbyTest, Bob's tab,
// build/frames.jsonl): the same gestures, the same frames, the DOM as the player would see it.
// Prints what it sees; the hard checks are the few `must` lines.
import { createRequire } from 'module';
import fs from 'fs';
const require = createRequire('/home/app/kroom-webapp-assets/src/test/js/');
const { JSDOM, VirtualConsole } = require('jsdom');
const R = '/home/app/kroom-examples/chifoumi/src/main/resources';
const A = '/home/app/kroom-webapp-assets/src/main/resources/static/js';
const frames = fs.readFileSync('/home/app/kroom-examples/chifoumi/build/frames.jsonl', 'utf8').split('\n').filter(Boolean).map(JSON.parse);
let failed = 0;
const must = (cond, label) => { if (!cond) { failed++; console.log('FAIL', label); } };
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function page(template, script) {
    let html = fs.readFileSync(`${R}/templates/${template}`, 'utf8')
        .replace(/<script[^>]*><\/script>/g, '').replace(/#foreach[\s\S]*?#end/g, '').replace(/\$lang/g, 'en');
    const vc = new VirtualConsole();
    const log = [];
    vc.on('jsdomError', e => log.push('JSDOMERR ' + e.message.split('\n')[0]));
    vc.on('error', (...a) => log.push('ERR ' + a.join(' ')));
    const dom = new JSDOM(html, { runScripts: 'outside-only', virtualConsole: vc, url: 'http://x/en/' + template });
    const w = dom.window;
    const posts = [];
    let reply = {};
    w.fetch = async (url, opts = {}) => {
        const body = opts.body ? JSON.parse(opts.body) : null;
        if (url.endsWith('/session')) return new Response(JSON.stringify({ id: 'b', name: 'Bob' }), { headers: { 'content-type': 'application/json' } });
        posts.push(body);
        return new Response(JSON.stringify(reply), { status: 200, headers: { 'content-type': 'application/json' } });
    };
    const listeners = {};
    w.EventSource = class { constructor(u) { this.url = u; w.__es = this; } addEventListener(n, f) { listeners[n] = f; } close() {} };
    const src = [];
    for (const f of ['domhelper', 'api', 'sse', 'intents', 'status'].map(n => `${A}/${n}.js`).concat([`${R}/static/js/state.js`, `${R}/static/js/${script}`]))
        src.push(fs.readFileSync(f, "utf8"));
    w.eval(src.join("\n;\n"));
    w.document.dispatchEvent(new w.Event('DOMContentLoaded'));
    await sleep(50);
    const send = async (f) => { listeners[f.event]?.({ data: JSON.stringify(f.data) }); await sleep(5); };
    const $t = s => w.document.querySelector(s)?.textContent.trim().replace(/\s+/g, ' ');
    const vis = s => { const e = w.document.querySelector(s); return e && e.style.display !== 'none' && !e.hidden; };
    return { w, send, $t, vis, posts, log, setReply: r => reply = r, click: async s => { w.document.querySelector(s).click(); await sleep(30); } };
}

// lobby
{
    const p = await page('index.html', 'chifoumi.js');
    for (const f of frames.slice(0, 3)) await p.send(f);
    console.log('lobby online', p.$t('#online-num'), '| queue', p.$t('#queue-count'), '| feed', p.$t('#activity-feed'));
    p.setReply({ pending: true });
    await p.click('[data-intent="match"]');
    await p.send({ event: 'match', data: { player: 'b' } });
    console.log('find match posted', JSON.stringify(p.posts.at(-1)), '| searching', p.vis('#searching'), '| queue-status', p.vis('#queue-status'));
    must(p.posts.at(-1).type === 'match' && p.vis('#searching') && !p.vis('#queue-status'), 'find match: searching');
    p.setReply({});
    await p.click('#searching [data-intent="cancel"]');
    await p.send({ event: 'cancel', data: { intent: 'match', user: 'b' } });
    console.log('cancel posted', JSON.stringify(p.posts.at(-1)), '| searching', p.vis('#searching'), '| queue', JSON.stringify(p.$t('#queue-count')));
    must(p.posts.at(-1).intent === 'match' && !p.vis('#searching'), 'cancel: back to the button');
    p.w.document.querySelector('#chat-form [name="text"]').value = 'hello';
    await p.click('[data-intent="chat"]');
    console.log('chat posted', JSON.stringify(p.posts.at(-1)), '| input now', JSON.stringify(p.w.document.querySelector('#chat-form [name="text"]').value));
    must(p.posts.at(-1).text === 'hello' && p.w.document.querySelector('#chat-form [name="text"]').value === '', 'chat: posted and cleared');
    await p.send(frames[4]);
    console.log('chat log', p.$t('#chat-log'));
    await p.send({ event: 'round', data: { match: { ...frames[3].data.match, player1: 'x', player2: 'y', name1: 'X', name2: 'Y', id: 'm2', finished: true, winner: 'y', score1: 3, score2: 5 }, round: { move1: 'rock', move2: 'well' } } });
    console.log('stats', p.$t('.stats-grid'), '| feed', p.$t('#activity-feed'));
    await p.send(frames[3]);
    console.log('after pairing:', p.log.join(' / ') || 'no navigation logged');
}
// game
{
    const p = await page('game.html', 'game.js');
    const snap = { ...frames[0], data: { ...frames[0].data, matches: { [frames[3].data.match.id]: frames[3].data.match } } };
    await p.send(snap);
    console.log('game names', p.$t('#player1-name'), '/', p.$t('#player2-name'), '| msg', p.$t('#game-message'));
    await p.send(frames[5]);
    console.log('after a plays:', p.$t('#game-message'));
    await p.click('[data-move="paper"]');
    console.log('play posted', JSON.stringify(p.posts.at(-1)));
    await p.send(frames[6]);
    console.log('after my play:', p.$t('#game-message'), '| take-back', p.vis('#take-back'), '| disabled', [...p.w.document.querySelectorAll('.move-btn')].map(b => b.disabled).join(), '| selected', p.w.document.querySelector('.move-btn.selected')?.dataset.move);
    await p.send(frames[7]);
    console.log('round:', p.$t('#round-message'), '|', p.$t('#round-verdict'), '| scores', p.$t('#player1-score'), p.$t('#player2-score'), '| moves', p.$t('#your-move .emoji'), p.$t('#opponent-move .emoji'));
    must(p.$t('#round-verdict') === 'Paper covers the rock.' && p.$t('#player2-score') === '1', 'round: the verdict and the score');
    await sleep(2100);
    console.log('next round:', p.$t('#game-message'), '| buttons', p.vis('#move-buttons'), '| round', p.$t('#round-num'));
    await p.send(frames[8]);
    await p.click('#take-back');
    console.log('take back posted', JSON.stringify(p.posts.at(-1)));
    await p.send(frames[9]);
    console.log('after take back:', p.$t('#game-message'), '| take-back', p.vis('#take-back'), '| disabled', [...p.w.document.querySelectorAll('.move-btn')].map(b => b.disabled).join());
    await p.send({ event: 'cancel', data: { intent: 'play', user: 'a' } });
    console.log('opponent took back:', p.$t('#game-message'));
    for (const f of frames.slice(10, 13)) await p.send(f);
    await sleep(2100);
    await p.send(frames[13]);
    await p.send(frames[14]);
    console.log('forfeit:', p.$t('#match-result-title'), '|', p.$t('#match-result-message'), '| result', p.vis('#match-result'), '| play again', p.vis('#play-again-btn'));
    must(p.vis('#match-result') && p.$t('#match-result-message').includes('Alice'), 'forfeit: victory over the leaver');
    p.setReply({ pending: true });
    await p.click('#play-again-btn');
    await p.send({ event: 'match', data: { player: 'b' } });
    console.log('play again posted', JSON.stringify(p.posts.at(-1)), '| searching', p.vis('#again-searching'), '| play again', p.vis('#play-again-btn'));
    await p.send({ event: 'match', data: { player: 'b', match: { ...frames[3].data.match, id: 'new1' } } });
    console.log('new match:', p.log.join(' / ') || 'nothing logged');
}
console.log(failed ? `${failed} check(s) failed` : 'all checks passed');
process.exit(failed ? 1 : 0);
