// The chifoumi pages in jsdom, driven by the frames a JVM test recorded (LobbyTest, Bob's tab, the golden
// in chifoumi-common's test resources): the same gestures, the same frames, the DOM as the player would see it.
// Prints what it sees; the hard checks are the few `must` lines.
import { createRequire } from 'module';
import fs from 'fs';
const require = createRequire('/home/app/kroom-webapp-assets/src/test/js/');
const { JSDOM, VirtualConsole } = require('jsdom');
const R = '/home/app/kroom-examples/chifoumi/src/main/resources';
const A = '/home/app/kroom-webapp-assets/src/main/resources/static/js';
// both pages run on the bundle (chifoumi-common's js executable: the fold, the compiled pages, the app's main — build it first)
const BUNDLE = process.env.BUNDLE || '/home/app/kroom-examples/chifoumi-common/build/dist/js/productionExecutable/arena.js';
for (const kind of ['uncaughtException', 'unhandledRejection']) process.on(kind, e => { failed++; console.log(kind.toUpperCase(), e?.message ?? e, '|', String(e?.stack ?? '').split('\n').slice(1, 5).join(' | ')); });
const LOBBY = ['domhelper', 'api', 'status'].map(n => `${A}/${n}.js`).concat([BUNDLE, `${R}/static/js/chifoumi.js`]);
const GAME = ['domhelper', 'api'].map(n => `${A}/${n}.js`).concat([BUNDLE, `${R}/static/js/game.js`]);
const frames = fs.readFileSync('/home/app/kroom-examples/chifoumi-common/src/commonTest/resources/frames.jsonl', 'utf8').split('\n').filter(Boolean).map(JSON.parse).filter(f => f.event !== 'final');
let failed = 0;
const must = (cond, label) => { if (!cond) { failed++; console.log('FAIL', label); } };
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function page(template, scripts) {
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
    for (const f of scripts) src.push(fs.readFileSync(f, "utf8"));
    w.eval(src.join("\n;\n"));
    w.document.dispatchEvent(new w.Event('DOMContentLoaded'));
    await sleep(50);
    const send = async (f) => { listeners[f.event]?.({ data: JSON.stringify(f.data) }); await sleep(5); };
    const $t = s => w.document.querySelector(s)?.textContent.trim().replace(/\s+/g, ' ');
    const vis = s => { for (let e = w.document.querySelector(s); e; e = e.parentElement) if (e.hidden || e.style.display === 'none') return false; return !!w.document.querySelector(s); };   // shown = no hidden ancestor
    return { w, send, $t, vis, posts, log, setReply: r => reply = r, click: async s => { w.document.querySelector(s).click(); await sleep(30); } };
}

// lobby
{
    const p = await page('index.html', LOBBY);
    for (const f of frames.slice(0, 3)) await p.send(f);
    console.log('lobby online', p.$t('#online-num'), '| me', p.$t('#player-display'), '| queue', p.$t('#queue-count'), '| feed', p.$t('#activity-feed'));
    must(p.$t('#online-num') === '2' && p.$t('#player-display') === 'Bob', 'lobby: online count and the viewer from the snapshot');
    must(p.vis('#queue-count') && /1 waiting/.test(p.$t('#queue-count')), 'lobby: alice waits');
    p.setReply({ pending: true });
    await p.click('[data-kroom-intent="match"]');
    await p.send({ event: 'match', data: { player: 'b' } });
    console.log('find match posted', JSON.stringify(p.posts.at(-1)), '| searching', p.vis('#searching'), '| queue-status', p.vis('#queue-status'));
    must(p.posts.at(-1).type === 'match' && p.vis('#searching') && !p.vis('#queue-status'), 'find match: searching');
    p.setReply({});
    await p.click('#searching [data-kroom-intent="cancel"]');
    await p.send({ event: 'cancel', data: { intent: 'match', user: 'b' } });
    console.log('cancel posted', JSON.stringify(p.posts.at(-1)), '| searching', p.vis('#searching'), '| queue', JSON.stringify(p.$t('#queue-count')));
    must(p.posts.at(-1).intent === 'match' && !p.vis('#searching'), 'cancel: back to the button');
    p.w.document.querySelector('#chat-form [name="text"]').value = 'hello';
    await p.click('[data-kroom-intent="chat"]');
    console.log('chat posted', JSON.stringify(p.posts.at(-1)), '| input now', JSON.stringify(p.w.document.querySelector('#chat-form [name="text"]').value));
    must(p.posts.at(-1).text === 'hello' && p.w.document.querySelector('#chat-form [name="text"]').value === '', 'chat: posted and cleared');
    await p.send(frames[4]);
    console.log('chat log', p.$t('#chat-log'));
    await p.send({ event: 'round', data: { match: { ...frames[3].data.match, player1: 'x', player2: 'y', name1: 'X', name2: 'Y', id: 'm2', finished: true, winner: 'y', score1: 3, score2: 5 }, round: { move1: 'rock', move2: 'well' } } });
    console.log('stats', p.$t('.stats-grid'), '| feed', p.$t('#activity-feed'));
    must(p.$t('#stat-matches') === '1' && p.$t('#stat-rock') === '1' && p.$t('#stat-well') === '1' && /X beat|Y beat/.test(p.$t('#activity-feed')), 'stats and feed after a round');
    await p.send(frames[3]);
    console.log('after pairing:', p.log.join(' / ') || 'no navigation logged');
    must(p.log.some(l => /navigation/.test(l)), 'pairing: the page follows to the game');
}
// game
{
    const p = await page('game.html', GAME);
    const snap = { ...frames[0], data: { ...frames[0].data, matches: { [frames[3].data.match.id]: frames[3].data.match } } };
    await p.send(snap);
    console.log('game names', p.$t('#player1-name'), '/', p.$t('#player2-name'), '| msg', p.$t('#game-message'), '| buttons', p.vis('#move-buttons'));
    must(p.$t('#player1-name') === 'Alice' && p.$t('#player2-name') === 'You' && p.vis('#move-buttons'), 'game: the perspective from the snapshot');
    await p.send(frames[5]);
    console.log('after a plays:', p.$t('#game-message'));
    must(/Alice has played/.test(p.$t('#game-message')), 'opponent played: the fold says so');
    await p.click('[data-kroom-move="paper"]');
    console.log('play posted', JSON.stringify(p.posts.at(-1)));
    must(p.posts.at(-1).type === 'play' && p.posts.at(-1).move === 'paper', 'play: the move posted');
    await p.send(frames[6]);
    console.log('after my play:', p.$t('#game-message'), '| played', p.$t('#played'), '| take-back', p.vis('#take-back'), '| buttons', p.vis('#move-buttons'));
    must(p.vis('#take-back') && !p.vis('#move-buttons') && /📄/.test(p.$t('#played')), 'waiting: my move shown, the buttons gone');
    await p.send(frames[7]);
    console.log('round:', p.$t('#round-message'), '|', p.$t('#round-verdict'), '| scores', p.$t('#player1-score'), p.$t('#player2-score'), '| moves', p.$t('#your-move .emoji'), p.$t('#opponent-move .emoji'), '| indicator', p.$t('.result-indicator'));
    must(p.vis('#round-result') && p.$t('#round-verdict') === 'Paper covers the rock.' && p.$t('#player2-score') === '1' && p.$t('#your-move .emoji') === '📄', 'round: the reveal, the verdict and the score');
    await sleep(2100);
    console.log('next round:', p.$t('#game-message'), '| buttons', p.vis('#move-buttons'), '| reveal', p.vis('#round-result'), '| round', p.$t('#round-num'));
    must(p.vis('#move-buttons') && !p.vis('#round-result') && p.$t('#round-num') === '2', 'after the reveal: round 2, the buttons back');
    await p.send(frames[8]);
    await p.click('#take-back');
    console.log('take back posted', JSON.stringify(p.posts.at(-1)));
    must(p.posts.at(-1).type === 'cancel' && p.posts.at(-1).intent === 'play', 'take back: cancel of play posted');
    await p.send(frames[9]);
    console.log('after take back:', p.$t('#game-message'), '| take-back', p.vis('#take-back'), '| buttons', p.vis('#move-buttons'));
    must(p.vis('#move-buttons') && !p.vis('#take-back'), 'after take back: choosing again');
    await p.send({ event: 'play', data: { player: 'a' } });
    await p.send({ event: 'cancel', data: { intent: 'play', user: 'a' } });
    console.log('opponent took back:', p.$t('#game-message'));
    must(/Alice took their move back/.test(p.$t('#game-message')), 'opponent took back: the note');
    for (const f of frames.slice(10, 13)) await p.send(f);
    await sleep(2100);
    await p.send(frames[13]);
    await p.send(frames[14]);
    const title = [...p.w.document.querySelectorAll('#match-result h2')].find(h => !h.hidden);
    console.log('forfeit:', title?.textContent, title?.className, '|', p.$t('#match-result-message'), '| result', p.vis('#match-result'), '| play again', p.vis('#play-again-btn'));
    must(p.vis('#match-result') && title?.className === 'winner' && p.$t('#match-result-message').includes('Alice') && p.vis('#play-again-btn'), 'forfeit: victory over the leaver');
    p.setReply({ pending: true });
    await p.click('#play-again-btn');
    await p.send({ event: 'match', data: { player: 'b' } });
    console.log('play again posted', JSON.stringify(p.posts.at(-1)), '| searching', p.vis('#again-searching'), '| play again', p.vis('#play-again-btn'));
    must(p.vis('#again-searching') && !p.vis('#play-again-btn'), 'play again: searching');
    await p.send({ event: 'match', data: { player: 'b', match: { ...frames[3].data.match, id: 'new1', score1: 0, score2: 0, round: 1 } } });
    console.log('new match:', p.$t('#game-message'), '| buttons', p.vis('#move-buttons'), '| result', p.vis('#match-result'), '| round', p.$t('#round-num'), '|', p.log.join(' / ') || 'no navigation');
    must(p.vis('#move-buttons') && !p.vis('#match-result') && p.$t('#round-num') === '1' && !p.log.some(l => /navigation/.test(l)), 'new match: the page turns over, no reload');
}
console.log(failed ? `${failed} check(s) failed` : 'all checks passed');
process.exit(failed ? 1 : 0);
