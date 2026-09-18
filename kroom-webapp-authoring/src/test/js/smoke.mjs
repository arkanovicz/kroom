// What authoring.js does to the page, checked against a fake DOM and a fake server.
//
// Not part of `gradle test`: it needs node, which this house runs only in docker — `./smoke.sh` here, or
//   docker run --rm -u 1000:1000 -v "$PWD":/home/app -w /home/app node:24 \
//     bash -c "npm install --silent --no-fund --no-audit jsdom && node smoke.mjs"
// The editor is the one piece no ktor test can reach; leaving it entirely unexercised was the alternative.

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../../../kroom-webapp-assets/src/main/resources/static/js';
const OWN = '../../main/resources/static';
const PATH = 'pages/club/13Ma/description.md';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** A page holding one editable block, the scripts loaded as a browser loads them: one shared scope. */
function page(responder) {
    const dom = new JSDOM(`<!doctype html><html><body>
        <div class="kroom-block" data-content="${PATH}" data-api="/api/content" data-name="description">
          <nav class="kroom-block-tools"><button class="kroom-edit">e</button><button class="kroom-history">h</button></nav>
          <div class="kroom-block-body"><p>published text</p></div>
        </div></body></html>`, { runScripts: 'outside-only', url: 'http://localhost/club/13Ma' });
    const { window } = dom;
    const calls = [];
    // real Response objects: api.js branches on `instanceof Response` to carry a status and a payload
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        calls.push({ url, method: options.method || 'GET', body: options.body && JSON.parse(options.body) });
        const { status = 200, payload = {} } = responder(url, options.method || 'GET') || {};
        return new Response(JSON.stringify(payload), { status, headers: { 'content-type': 'application/json' } });
    };
    if (window.HTMLDialogElement) window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
    window.eval(['domhelper.js', 'api.js'].map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
        .concat(readFileSync(`${OWN}/lib/diff-match-patch/diff_match_patch.js`, 'utf8'),
                readFileSync(`${OWN}/js/authoring.js`, 'utf8')).join('\n;\n'));
    const click = (selector) => window.document.querySelector(selector)
        ?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    return { window, calls, click, $: (s) => window.document.querySelector(s) };
}

const held = { payload: { body: '## Titre', rev: 'abc123', meta: { author: 'admin' }, lock: { owner: 'admin' }, editable: true } };
const previewOf = (body) => ({ payload: { page: `<html><body><div class="kroom-block" data-content="${PATH}"><div class="kroom-block-body"><p>${body}</p></div></div></body></html>` } });

// --- taking a block, and giving it back -----------------------------------------------------------
{
    const { calls, click, $ } = page((url) => url.includes('/lock/') ? held : { payload: { rev: 'def456' } });
    click('.kroom-edit');
    await sleep(20);
    check('edit takes the lock', calls[0], { url: `/api/content/lock/${PATH}`, method: 'POST', body: undefined });
    check('the stored body is what you edit', $('.kroom-block textarea')?.value, '## Titre');
    check('the published rendering is kept beside it', $('.kroom-preview')?.textContent.includes('published text'), true);

    click('.kroom-cancel');
    await sleep(20);
    check('cancel gives the lock back', calls[1], { url: `/api/content/lock/${PATH}`, method: 'DELETE', body: undefined });
    check('cancel restores the block', $('.kroom-block textarea'), null);
    check('the page is as it was', $('.kroom-block-body')?.textContent.trim(), 'published text');
}

// --- submitting -----------------------------------------------------------------------------------
{
    const { calls, click, $, window } = page((url) =>
        url.includes('/lock/') ? held : url.includes('/preview/') ? previewOf('rendu du serveur') : { payload: { rev: 'def456' } });
    click('.kroom-edit');
    await sleep(20);
    const textarea = $('.kroom-block textarea');
    textarea.value = '## Titre\n\nnouveau';
    textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
    await sleep(900);
    check('typing re-renders the page and shows this block of it',
        $('.kroom-preview .kroom-block-body')?.textContent.trim(), 'rendu du serveur');
    check('the preview asks for the page it is in', calls[1],
        { url: `/api/content/preview/${PATH}`, method: 'POST', body: { page: '/club/13Ma', body: '## Titre\n\nnouveau' } });
    // typing back to what was already rendered costs a heartbeat, not a render
    textarea.value = '## Titre\n\nnouveau';
    textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
    await sleep(900);
    check('an unchanged body is not re-rendered', calls.filter(c => c.url.includes('/preview/')).length, 1);

    click('.kroom-submit');
    await sleep(20);
    check('submit carries the rev it started from', calls[calls.length - 1],
        { url: `/api/content/${PATH}`, method: 'POST', body: { rev: 'abc123', body: '## Titre\n\nnouveau' } });
}

// --- someone else wrote meanwhile -----------------------------------------------------------------
{
    const conflict = { status: 409, payload: { message: 'stale', theirs: { body: '## Titre\n\nle leur', rev: 'zzz' } } };
    const { click, $ } = page((url) => url.includes('/lock/') ? held : conflict);
    click('.kroom-edit');
    await sleep(20);
    $('.kroom-block textarea').value = '## Titre\n\nle mien';
    click('.kroom-submit');
    await sleep(20);
    check('a stale submit opens a diff instead of overwriting', !!$('.kroom-diff'), true);
    check('your text is still yours to keep editing', $('.kroom-block textarea')?.value, '## Titre\n\nle mien');
}

// --- history --------------------------------------------------------------------------------------
{
    const log = { payload: [{ rev: 'abc123', path: PATH, author: 'admin', time: 1757000000000 },
                            { rev: 'old999', path: PATH, author: 'nestor', time: 1756000000000 }] };
    const { click, $, window } = page((url) => url.includes('/history/') ? log : held);
    click('.kroom-history');
    await sleep(20);
    check('history lists what the store remembers', window.document.querySelectorAll('.kroom-dialog-body li, .kroom-dialog-body tr').length >= 2, true);
}

console.log(failures ? `\n${failures} failure(s)` : '\nall good');
process.exit(failures ? 1 : 0);
