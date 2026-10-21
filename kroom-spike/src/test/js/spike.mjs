// The page of spec §6.2 in jsdom, on the spike's bundle: a snapshot, then frames; the checks are
// node identity (a status frame touches one text node and nothing else: no flicker) and the fold
// property (the DOM after the frames equals a fresh render of the final snapshot).
import { createRequire } from 'module';
import fs from 'fs';
const require = createRequire('/home/app/kroom-webapp-assets/src/test/js/');
const { JSDOM } = require('jsdom');
const BUNDLE = process.env.BUNDLE || '/home/app/kroom-spike/build/kotlin-webpack/js/productionExecutable/spike.js';
let failed = 0;
const must = (cond, label) => { if (!cond) { failed++; console.log('FAIL', label); } else console.log('ok  ', label); };
const tick = () => new Promise(r => setTimeout(r, 0));

function mount() {
    const dom = new JSDOM('<!doctype html><body></body>', { runScripts: 'outside-only' });
    const w = dom.window;
    w.eval(fs.readFileSync(BUNDLE, 'utf8'));
    return { w, page: w.spike(w.document.body) };
}

const { w, page } = mount();
const d = w.document;
const text = () => d.body.textContent;
page.snapshot(JSON.stringify({ users: { 5: { name: 'Alice', status: 'ONLINE' }, 7: { name: 'Bob', status: 'ONLINE' } } }));
must(text() === '2 online\nAlice (ONLINE)Bob (ONLINE)', 'snapshot rendered: ' + JSON.stringify(text()));

const li5 = d.getElementById('users.5'), li7 = d.getElementById('users.7');
const name5 = li5.childNodes[0], status5 = li5.childNodes[2];
const records = [];
new w.MutationObserver(rs => records.push(...rs)).observe(d.body, { subtree: true, childList: true, characterData: true, attributes: true });

page.frame('status', JSON.stringify({ user: '5', status: 'AWAY' }));
await tick();
must(d.getElementById('users.5') === li5, 'item 5 keeps its identity');
must(li5.childNodes[0] === name5 && name5.data === 'Alice', 'name node untouched');
must(li5.childNodes[2] === status5 && status5.data === 'AWAY', 'status site updated in place');
must(d.getElementById('users.7') === li7 && li7.childNodes[2].data === 'ONLINE', 'item 7 untouched');
must(records.length === 1 && records[0].type === 'characterData' && records[0].target === status5, 'exactly one mutation, the status text node: ' + records.map(r => r.type + ':' + r.target.nodeName).join(','));

records.length = 0;
page.frame('join', JSON.stringify({ user: { id: '9', name: 'Carol', status: 'ONLINE' }, first: true, count: 3 }));
await tick();
must(text() === '3 online\nAlice (AWAY)Bob (ONLINE)Carol (ONLINE)', 'join appends and counts: ' + JSON.stringify(text()));
must(d.getElementById('users.5') === li5, 'item 5 still the same node after a join');
must(records.length === 2, 'a join is two mutations (the item, the count): ' + records.length);

page.frame('leave', JSON.stringify({ user: { id: '7', name: 'Bob' }, last: true, count: 2 }));
await tick();
must(text() === '2 online\nAlice (AWAY)Carol (ONLINE)', 'leave removes and counts: ' + JSON.stringify(text()));
must(d.getElementById('users.5') === li5 && li5.childNodes[2] === status5, 'item 5 still the same node after a leave');

page.frame('chat', JSON.stringify({ text: 'hi', seq: 1, from: 'Alice' }));
await tick();
must(text() === '2 online\nAlice (AWAY)Carol (ONLINE)', 'a frame on an absent field changes nothing');

// spec §8: the DOM after the fold equals the DOM rendered from the final snapshot
const fresh = mount();
fresh.page.snapshot(JSON.stringify({ users: { 5: { name: 'Alice', status: 'AWAY' }, 9: { name: 'Carol', status: 'ONLINE' } } }));
must(fresh.w.document.body.innerHTML === d.body.innerHTML, 'fold == render(final snapshot)');

console.log(failed ? `${failed} FAILED` : 'all ok');
process.exit(failed ? 1 : 0);
