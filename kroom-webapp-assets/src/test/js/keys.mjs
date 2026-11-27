// What keys.js decides: who owns a key where, what throws at registration, where a declined key goes.
// Not part of `gradle test`: `./smoke.sh` here (node runs in docker).

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../main/resources/static/js';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};
const throws = (fn) => { try { fn(); return null; } catch (e) { return e.message; } };

/** A page: a thread with a button, an editor holding a textarea and a paragraph, a search field. */
function page() {
    const dom = new JSDOM(`<!doctype html><html><body>
        <section id="thread"><button>more</button></section>
        <div class="editor"><textarea></textarea><p tabindex="-1">held</p></div>
        <input id="search" type="text"><input id="box" type="checkbox">
        </body></html>`, { runScripts: 'outside-only', url: 'http://localhost/' });
    const { window } = dom;
    window.eval(readFileSync(`${ASSETS}/keys.js`, 'utf8'));
    const $ = (s) => window.document.querySelector(s);
    const heard = [];
    /** A key pressed where the focus is (on [at], focused first; null: nothing focused): whether it was handled. */
    const press = (key, at, init = {}) => {
        if (at) at.focus(); else window.document.activeElement?.blur();
        const event = new window.KeyboardEvent('keydown', { key, cancelable: true, bubbles: true, ...init });
        (at || window.document.body).dispatchEvent(event);
        return event.defaultPrevented;
    };
    const by = (name, answer) => () => { heard.push(name); return answer; };
    return { window, keys: window.kroomKeys, $, press, heard, by };
}

// --- contexts ---------------------------------------------------------------------------------------
{
    const { keys, $, press, heard, by } = page();
    keys.on('page', 'Home', 'idle', by('idle'));
    keys.on('editor', 'Home', $('.editor'), by('editor'));
    check('idle: nothing focused', [press('Home', null), heard], [true, ['idle']]);
    check('an element: the focus inside it', [press('Home', $('textarea')), heard], [true, ['idle', 'editor']]);
    check('neither: the browser\'s', [press('Home', $('#search')), heard.length], [false, 2]);
}
{
    const { keys, $, press, heard, by } = page();
    keys.on('page', 'End', 'unfielded', by('unfielded'));
    check('unfielded: nothing focused', press('End', null), true);
    check('a button', press('End', $('button')), true);
    check('a checkbox is not typed in', press('End', $('#box')), true);
    check('a text field is', press('End', $('#search')), false);
    check('a textarea too', press('End', $('textarea')), false);
    check('heard', heard.length, 3);
}

// --- spelling -----------------------------------------------------------------------------------------
{
    const { keys, press, heard, by } = page();
    keys.on('page', ['ctrl+B', 'Space', 'Shift+Space', '?'], 'anywhere', e => heard.push(e.key + (e.shiftKey ? '⇧' : '')));
    press('b', null, { metaKey: true });
    press('B', null, { ctrlKey: true, shiftKey: true });   // Mod+Shift+b: not claimed
    press(' ', null);
    press(' ', null, { shiftKey: true });
    press('?', null, { shiftKey: true });                    // `?` says its shift
    check('Cmd is Mod, a letter\'s case is its shift, Space, `?`', heard, ['b', ' ', ' ⇧', '?⇧']);
    check('listed as spelled', keys.list().map(c => c.key), ['Mod+b', 'Space', 'Shift+Space', '?']);
    check('an unknown modifier throws', throws(() => keys.on('x', 'Hyper+a', 'anywhere', () => {})), 'kroomKeys: no modifier Hyper in Hyper+a');
    check('so does an unknown context', !!throws(() => keys.on('x', 'a', 'somewhere', () => {})), true);
}

// --- collisions ------------------------------------------------------------------------------------------
{
    const { keys, $ } = page();
    keys.on('arkad', 'PageUp', 'unfielded', () => {});
    check('two peers throw, naming both', throws(() => keys.on('host', 'PageUp', 'idle', () => {})),
        'kroomKeys: PageUp of host (idle) can hold with arkad\'s (unfielded), and neither is inside the other');
    check('an element inside a page-wide claim that does not yield', throws(() => keys.on('kroom', 'PageUp', $('.editor'), () => {})),
        'kroomKeys: PageUp of kroom (div.editor) can hold with arkad\'s (unfielded), and arkad\'s, the outer one, does not yield');
    check('a field cannot hold with unfielded', throws(() => keys.on('search', 'PageUp', $('#search'), () => {})), null);
    check('idle and an element never hold at once', throws(() => keys.on('x', 'Home', 'idle', () => {}) && keys.on('y', 'Home', $('.editor'), () => {})), null);
    check('disjoint elements', throws(() => keys.on('a', 'F2', $('#thread'), () => {}) && keys.on('b', 'F2', $('.editor'), () => {})), null);
    check('nested elements, the outer one not yielding', !!throws(() => keys.on('c', 'F2', $('textarea'), () => {})), true);
    check('nothing half-registered by a throw', keys.list().filter(c => c.owner === 'c' || c.owner === 'kroom' || c.owner === 'host'), []);
}

// --- Char: any character typed ---------------------------------------------------------------------------
{
    const { keys, $, press, heard } = page();
    keys.on('text', 'Char', $('.editor'), e => heard.push(e.key));
    press('a', $('p'));
    press('A', $('p'), { shiftKey: true });
    press('@', $('p'), { ctrlKey: true, altKey: true, getModifierState: undefined });   // a plain Ctrl+Alt+@: not typed
    press('é', $('p'));
    press('😀', $('p'));
    check('a character, shifted or not, beyond one code unit', heard, ['a', 'A', 'é', '😀']);
    check('not Space, nor Ctrl+a, nor a named key', [press(' ', $('p')), press('a', $('p'), { ctrlKey: true }), press('Enter', $('p'))], [false, false, false]);
    check('a character covered collides', throws(() => keys.on('page', '?', 'anywhere', () => {})),
        'kroomKeys: ? of page (anywhere) can hold with text\'s Char (div.editor), and page\'s, the outer one, does not yield');
    check('Shift+a too', !!throws(() => keys.on('page', 'Shift+a', 'anywhere', () => {})), true);
    check('not a key it does not cover', [throws(() => keys.on('page', 'Mod+a', 'anywhere', () => {})), throws(() => keys.on('page', 'Space', 'anywhere', () => {}))], [null, null]);
    keys.on('arkad', 'Char', 'unfielded', e => heard.push('arkad ' + e.key), { yields: true });
    heard.length = 0;
    press('x', $('button'));
    check('a page-wide Char around it, yielding', heard, ['arkad x']);
}
{
    // jsdom has no AltGraph state: a stand-in for the event Windows sends
    const { window, keys, $, heard } = page();
    keys.on('text', 'Char', $('.editor'), e => heard.push(e.key));
    $('p').focus();
    const event = new window.KeyboardEvent('keydown', { key: '@', ctrlKey: true, altKey: true, cancelable: true });
    event.getModifierState = (m) => m === 'AltGraph';
    $('p').dispatchEvent(event);
    check('AltGr, which Windows says as Ctrl+Alt, types a character', [heard, event.defaultPrevented], [['@'], true]);
}

// --- yielding: the inner claim first, a decline goes out --------------------------------------------------
{
    const { keys, $, press, heard, by } = page();
    let more = true;
    keys.on('arkad', 'PageDown', ['unfielded', $('#search')], by('arkad'), { yields: true });
    keys.on('kroom', 'PageDown', $('.editor'), () => { heard.push('kroom'); return more; });
    check('inside the editor, the editor\'s', [press('PageDown', $('p')), heard], [true, ['kroom']]);
    more = false;
    check('declined: the outer one\'s', [press('PageDown', $('p')), heard], [true, ['kroom', 'kroom', 'arkad']]);
    check('in the textarea (a field), kroom declining: the browser\'s', [press('PageDown', $('textarea')), heard.slice(3)], [false, ['kroom']]);
    check('arkad\'s own field', [press('PageDown', $('#search')), heard.slice(4)], [true, ['arkad']]);
    keys.on('page', 'PageDown', 'anywhere', by('page'), { yields: true });
    keys.on('thread', 'PageDown', $('#thread'), by('thread', false));
    heard.length = 0;
    press('PageDown', $('button'));
    check('a chain of three, inner to outer', heard, ['thread', 'arkad']);
}

// --- what a claim lives as long as ---------------------------------------------------------------------------
{
    const { window, keys, $, press } = page();
    const off = keys.on('editor', 'Escape', $('.editor'), () => {});
    check('listed, its context described', keys.list(), [{ owner: 'editor', key: 'Escape', context: ['div.editor'], yields: false }]);
    off();
    check('taken back', keys.list(), []);
    const box = window.document.body.appendChild(window.document.createElement('div'));
    keys.on('dialog', 'Escape', box, () => {});
    box.remove();
    check('an element gone claims nothing', throws(() => keys.on('page', 'Escape', 'anywhere', () => {})), null);
    check('and is listed no more', keys.list(), [{ owner: 'page', key: 'Escape', context: ['anywhere'], yields: false }]);
    check('a key composed is not a key', press('Escape', null, { isComposing: true }), false);
}

if (failures) { console.log(`${failures} failure(s)`); process.exit(1); }
