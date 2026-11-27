// editor.js as a plain-JS page loads it: kroom-markdown-editor's bundle, as this jar ships it, opening the frames it
// finds at load. Not part of `gradle test`: `./smoke.sh` here, after `./gradlew :kroom-webapp-assets:processResources`.

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../main/resources/static/js';
const BUNDLE = '../../../build/resources/main/static/js/editor.js';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};

const { window } = new JSDOM(`<!doctype html><body>
<section class="kroom-editor" data-room="pad" data-preview="/api/pad/html">
  <nav class="kroom-editor-bar"><button type="button" class="kroom-editor-preview-toggle">Preview</button></nav>
  <div class="kroom-editor-panes"><div class="kroom-text"></div><article class="kroom-editor-preview" hidden></article></div>
</section></body>`, { runScripts: 'outside-only', url: 'http://localhost/' });

window.eval(`
    window.opened = []; window.streams = {};
    window.api = { base: '/api/', tabId: function() { return 't1'; }, act: function() { return Promise.resolve({}); } };
    window.EventSource = function(url) { window.opened.push(url); var on = window.streams[url] = {};
        this.addEventListener = function(name, fn) { on[name] = fn; }; this.close = function() {}; };
`);
window.eval(readFileSync(`${ASSETS}/keys.js`, 'utf8'));
window.eval(readFileSync(`${ASSETS}/markdown.js`, 'utf8'));
window.eval(readFileSync(BUNDLE, 'utf8'));
if (window.document.readyState === 'loading') await new Promise(done => window.document.addEventListener('DOMContentLoaded', done));   // the frames open once the page is read

const frame = window.document.querySelector('.kroom-editor');
const stream = '/api/rooms/pad/stream?tabId=t1';
check('the global is there', typeof window.kroomEditor?.open, 'function');
check('the frame found at load is opened on the room it names', window.opened, [stream]);
check('the toolbar heads the bar', frame.querySelector('.kroom-editor-bar').firstElementChild?.className, 'kroom-format');
check('the writer\'s keys and the shortcuts, the focus in the text', window.kroomKeys.list().map(c => `${c.owner} ${c.context} ${c.key}`),
    ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Char', 'Mod+b', 'Mod+i', 'Mod+k'].map(k => `kroom-markdown-editor div.kroom-text ${k}`));

window.streams[stream].state({ data: '{"parts":[{"id":"1","text":"One."}],"last":1,"me":{"id":"a","name":"Alice"}}' });
check('the snapshot is merged in the region', frame.querySelector('.kroom-text p')?.textContent, 'One.');
window.streams[stream].take({ data: '{"ids":["1"],"user":"a","client":"t1"}' });
check('the part this tab takes is a textarea', frame.querySelector('.kroom-text textarea')?.value, 'One.');
check('the frame says it is written in', frame.hasAttribute('data-writing'), true);

console.log(failures ? `\n${failures} FAILED` : '\nall green');
process.exit(failures ? 1 : 0);
