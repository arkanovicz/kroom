// What gallery.js does to the page, checked against a fake DOM and a fake server.
//
// Not part of `gradle test`: it needs node, which this house runs only in docker — `./smoke.sh` here.

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../../../../../kroom-webapp-assets/src/main/resources/static/js';
const OWN = '../../main/resources/static';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const esc = (s) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
const figure = (name, fr, en) => `<figure data-media="k7f3a-${name}.jpg" data-captions="${esc(JSON.stringify({ fr, en }))}">`
    + `<a href="/img/k7f3a-${name}.jpg?size=900" data-full="/media/k7f3a-${name}.jpg"><img src="/img/k7f3a-${name}.jpg?size=300" alt="${fr}" loading="lazy"></a>`
    + `<figcaption>${fr}</figcaption></figure>`;
const THREE = [figure('plage', 'La plage', 'The beach'), figure('port', 'Le port', 'The harbour'), figure('phare', 'Le phare', 'The lighthouse')];
const media = (html) => [...html.matchAll(/data-media="([^"]+)"/g)].map(m => m[1]);

/** A page holding one gallery, the scripts loaded as a browser loads them: one shared scope. */
function page({ figures = THREE, edit = false, hash = '', responder = () => ({}) } = {}) {
    const dom = new JSDOM(`<!doctype html><html><body><main>
        <div class="kroom-gallery" data-gallery="k7f3a" data-api="/api/gallery" data-media="/api/media" data-lang="fr"${edit ? ' data-edit="1"' : ''}>${figures.join('')}</div>
        </main></body></html>`, { runScripts: 'outside-only', url: `http://localhost/voyage${hash}` });
    const { window } = dom;
    const calls = [];
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        calls.push({ url, method: options.method || 'GET', body: typeof options.body === 'string' ? JSON.parse(options.body) : options.body });
        const { status = 200, payload = {} } = responder(url, options.method || 'GET', options) || {};
        return new Response(JSON.stringify(payload), { status, headers: { 'content-type': 'application/json' } });
    };
    // jsdom has no modal dialogs
    window.HTMLDialogElement.prototype.showModal = function () { this.open = true; this.focus(); };   // the focus goes in, as a browser moves it
    window.HTMLDialogElement.prototype.close = function () { this.open = false; };
    window.eval(['domhelper.js', 'api.js', 'keys.js'].map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
        .concat(readFileSync(`${OWN}/js/gallery.js`, 'utf8')).join('\n;\n'));
    const document = window.document;
    const click = (target) => (typeof target === 'string' ? document.querySelector(target) : target)
        ?.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true }));
    const key = (name) => document.dispatchEvent(new window.KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
    const $ = (s) => document.querySelector(s);
    const $$ = (s) => [...document.querySelectorAll(s)];
    const currentDot = () => $$('.kroom-gallery-dot').findIndex(d => d.classList.contains('active')) + 1;
    return { window, document, calls, click, key, $, $$, currentDot };
}

/** What the server answers a PUT: the gallery as stored, its own URL scheme. */
const stored = (items) => ({
    payload: {
        id: 'k7f3a', items: items.map(i => ({
            ...i, caption: i.captions.fr || '', thumb: `/img/${i.media}?size=300&v=2`, slide: `/img/${i.media}?size=900`, full: `/media/${i.media}`
        }))
    }
});

// --- the slideshow --------------------------------------------------------------------------------
{
    const { window, click, key, $, $$, currentDot } = page();
    await sleep(20);
    const before = window.history.length;
    check('a visitor gets no editor', [$('.kroom-gallery-tools'), $('.kroom-gallery-remove')], [null, null]);
    check('no slideshow before it is asked for', $('dialog'), null);
    click($$('.kroom-gallery > figure > a img')[1]);
    const dialog = $('body > dialog.kroom-gallery-show');
    check('the second thumbnail opens the show', dialog?.open, true);
    check('one slide per picture, lazy, at the fit size', $$('.kroom-gallery-slide img').map(i => [i.getAttribute('loading'), i.getAttribute('src')]),
        [['lazy', '/img/k7f3a-plage.jpg?size=900'], ['lazy', '/img/k7f3a-port.jpg?size=900'], ['lazy', '/img/k7f3a-phare.jpg?size=900']]);
    check('captioned', $$('.kroom-gallery-slide figcaption').map(c => c.textContent), ['La plage', 'Le port', 'Le phare']);
    check('on the second', currentDot(), 2);
    check('deep-linked', window.location.hash, '#k7f3a-2');
    check('with one history entry', window.history.length, before + 1);
    check('the page behind stops scrolling', window.document.body.classList.contains('kroom-showing'), true);
    check('download offers the original', [$('.kroom-gallery-download').getAttribute('href'), $('.kroom-gallery-download').download],
        ['/media/k7f3a-port.jpg', 'k7f3a-port.jpg']);

    click('.kroom-gallery-next');
    check('next', [currentDot(), window.location.hash], [3, '#k7f3a-3']);
    click('.kroom-gallery-next');
    check('next from the last wraps to the first', [currentDot(), window.location.hash], [1, '#k7f3a-1']);
    key('ArrowLeft');
    check('← from the first wraps to the last', currentDot(), 3);
    check('navigating replaces the entry', window.history.length, before + 1);
    click($$('.kroom-gallery-dot')[1]);
    check('a dot goes to its slide', currentDot(), 2);

    key(' ');
    check('space plays', $('.kroom-gallery-play').title, 'pause');
    key(' ');
    check('and pauses', $('.kroom-gallery-play').title, 'play');

    click('.kroom-gallery-zoom');
    check('zoom shows the original', [$('.kroom-gallery-zoomed').hidden, $('.kroom-gallery-zoomed img').getAttribute('src')], [false, '/media/k7f3a-port.jpg']);
    key('Escape');
    check('Escape closes the zoom first', [$('.kroom-gallery-zoomed').hidden, dialog.open], [true, true]);
    click('.kroom-gallery-zoom');
    click('.kroom-gallery-zoomed');
    check('a click closes it too', $('.kroom-gallery-zoomed').hidden, true);

    key('Escape');
    check('then Escape closes the show', [dialog.open, window.location.hash, window.document.body.classList.contains('kroom-showing')], [false, '', false]);
    click($$('.kroom-gallery > figure > a')[0]);
    check('built once per gallery', $$('dialog').length, 1);
    click(dialog);
    check('a backdrop click closes', dialog.open, false);
    click($$('.kroom-gallery > figure > a')[2]);
    click('.kroom-gallery-close');
    check('so does ✕', dialog.open, false);

    window.location.hash = '#k7f3a-2';
    await sleep(20);
    check('a hash change opens the show where it says', [dialog.open, currentDot()], [true, 2]);
    window.location.hash = '';
    await sleep(20);
    check('and its removal (Back) closes it', dialog.open, false);
}
{
    const { $, currentDot, window } = page({ hash: '#k7f3a-3' });
    await sleep(20);
    check('a page loaded with a deep link opens on that slide', [$('dialog')?.open, currentDot(), window.location.hash], [true, 3, '#k7f3a-3']);
}
{
    const many = Array.from({ length: 31 }, (_, i) => figure(`p${i}`, `photo ${i}`, `photo ${i}`));
    const { click, $, $$ } = page({ figures: many });
    await sleep(20);
    click($$('.kroom-gallery > figure > a')[4]);
    check('past 30 slides, a counter instead of dots', [$('.kroom-gallery-dots'), $('.kroom-gallery-counter')?.textContent], [null, '5 / 31']);
}

// --- the editor -----------------------------------------------------------------------------------
{
    const { window, document, calls, click, $, $$ } = page({ edit: true, responder: (url, method, options) =>
        method === 'PUT' ? stored(JSON.parse(options.body).items) : {} });
    await sleep(20);
    const gallery = $('.kroom-gallery');
    check('the toolbar sits before the grid', gallery.previousElementSibling?.className, 'kroom-gallery-tools');
    check('it offers to add photos', [$('.kroom-gallery-add')?.textContent, $('.kroom-gallery-tools input[type=file]')?.multiple,
        $('.kroom-gallery-tools input[type=file]')?.accept], ['Add photos', true, 'image/*']);
    check('every figure can be removed', $$('.kroom-gallery > figure .kroom-gallery-remove').length, 3);

    click($$('.kroom-gallery > figure > a')[0]);
    check('a click on a thumbnail still opens the show', $('dialog')?.open, true);
    $('.kroom-gallery-close').click();

    gallery.prepend(gallery.lastElementChild);
    await window.kroomGallery.sync(gallery);
    const put = calls.at(-1);
    check('a reorder writes the new order', [put.url, put.method, put.body.items.map(i => i.media)],
        ['/api/gallery/k7f3a', 'PUT', ['k7f3a-phare.jpg', 'k7f3a-plage.jpg', 'k7f3a-port.jpg']]);
    check('every language\'s caption travels', put.body.items[0].captions, { fr: 'Le phare', en: 'The lighthouse' });
    check('the reply is what is shown', $$('.kroom-gallery > figure > a > img').map(i => i.getAttribute('src')),
        ['/img/k7f3a-phare.jpg?size=300&v=2', '/img/k7f3a-plage.jpg?size=300&v=2', '/img/k7f3a-port.jpg?size=300&v=2']);
    check('still editable', $$('.kroom-gallery > figure .kroom-gallery-remove').length, 3);
    click($$('.kroom-gallery > figure > a')[0]);
    check('and the show follows the new order', $('.kroom-gallery-slide img')?.getAttribute('src'), '/img/k7f3a-phare.jpg?size=900');
    $('.kroom-gallery-close').click();

    const caption = $$('.kroom-gallery > figure > figcaption')[1];
    click(caption);
    const input = caption.querySelector('input');
    check('a caption edits the page\'s language', input?.value, 'La plage');
    input.value = 'La grande plage';
    input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await sleep(20);
    check('Enter writes it, other languages untouched', calls.at(-1).body.items[1].captions, { fr: 'La grande plage', en: 'The beach' });
    check('and the grid shows it', $$('.kroom-gallery > figure > figcaption')[1].textContent, 'La grande plage');
    const count = calls.length;
    const again = $$('.kroom-gallery > figure > figcaption')[0];
    click(again);
    again.querySelector('input').value = 'oops';
    again.querySelector('input').dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    check('Escape cancels', [calls.length, again.textContent], [count, 'Le phare']);

    window.confirm = () => true;
    click($$('.kroom-gallery-remove')[2]);
    await sleep(20);
    check('removing writes the rest', calls.at(-1).body.items.map(i => i.media), ['k7f3a-phare.jpg', 'k7f3a-plage.jpg']);
    check('the grid follows', $$('.kroom-gallery > figure').length, 2);
}
{
    const { window, calls, $, $$ } = page({ edit: true, responder: (url, method, options) =>
        method === 'PUT' ? stored(JSON.parse(options.body).items)
            : url.includes('Bad') ? { status: 413, payload: { message: 'too large' } }
            : { payload: { name: 'k7f3a-new.jpg', url: '/media/k7f3a-new.jpg', type: 'image/jpeg', size: 3, time: 0 } } });
    await sleep(20);
    const gallery = $('.kroom-gallery');
    const file = new window.File([new Uint8Array([1, 2, 3])], 'Ma photo.jpg', { type: 'image/jpeg' });
    const bad = new window.File([new Uint8Array([1])], 'Bad.jpg', { type: 'image/jpeg' });
    const over = new window.Event('dragover', { bubbles: true, cancelable: true });
    Object.defineProperty(over, 'dataTransfer', { value: { types: ['Files'] } });
    gallery.dispatchEvent(over);
    check('a file dragged over the gallery highlights it', gallery.classList.contains('kroom-gallery-over'), true);
    const drop = new window.Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(drop, 'dataTransfer', { value: { files: [file, bad], types: ['Files'] } });
    gallery.dispatchEvent(drop);
    await sleep(50);
    check('the highlight goes with the drop', gallery.classList.contains('kroom-gallery-over'), false);
    const post = calls.find(c => c.method === 'POST');
    check('a dropped file is posted as it is, its name in the query', [post?.url, post?.body === file], ['/api/media?name=Ma%20photo.jpg', true]);
    check('then one write appends it', [calls.filter(c => c.method === 'PUT').length, calls.at(-1).body.items.slice(-1)],
        [1, [{ media: 'k7f3a-new.jpg', captions: {} }]]);
    check('the grid shows it', $$('.kroom-gallery > figure').length, 4);
    check('a failed upload says so, no alert', $('.kroom-gallery-status').textContent, 'Bad.jpg: too large');

    // a paste with the page's only editable gallery: its pictures land there; with a field focused, nothing
    const pasted = new window.File([new Uint8Array([4, 5, 6])], 'Pasted.png', { type: 'image/png' });
    const paste = (target) => {
        const ev = new window.Event('paste', { bubbles: true, cancelable: true });
        Object.defineProperty(ev, 'clipboardData', { value: { files: [pasted] } });
        target.dispatchEvent(ev);
        return ev;
    };
    const before = calls.length;
    paste(window.document.body);
    await sleep(50);
    check('a paste on the page goes to its only editable gallery', calls.slice(before).map(c => c.method), ['POST', 'PUT']);
    check('the grid shows the pasted picture', $$('.kroom-gallery > figure').length, 5);
    const field = window.document.body.appendChild(window.document.createElement('input'));
    field.focus();
    const caret = calls.length;
    const ignored = paste(field);
    await sleep(50);
    check('a paste into a field is the field\'s', [calls.length - caret, ignored.defaultPrevented], [0, false]);
}
{
    const { window, $ } = page({ edit: true, responder: () => ({ status: 403, payload: { message: 'not allowed' } }) });
    await sleep(20);
    const gallery = $('.kroom-gallery');
    gallery.prepend(gallery.lastElementChild);
    await window.kroomGallery.sync(gallery);
    check('a refused write says so in the toolbar', $('.kroom-gallery-status').textContent, 'k7f3a: not allowed');
}

console.log(failures ? `\n${failures} failure(s)` : '\nall good');
process.exit(failures ? 1 : 0);
