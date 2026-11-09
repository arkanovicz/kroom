// gallery.js - a photo gallery: a grid of squares, a slideshow, and for its editors a sortable, captioned grid
// Part of kroom-plugin-gallery. Needs domhelper.js and api.js; SortableJS when editing.
//
// The server renders the grid as plain figures (each a link to its slide), so it reads without this script;
// here it gains the slideshow, and the editor when the page says data-edit.

(function () {

    const PLAY_DELAY = 3000;
    const MAX_DOTS = 30;              // past this a dot per slide is unreadable: a counter instead

    // English is the floor; the server emits the request's words ahead of this script
    const STOCK = {
        add: 'Add photos', drop: 'or drop them here', delete: 'Remove from the gallery',
        confirmDelete: 'Remove this picture from the gallery?', caption: 'Caption',
        close: 'close', previous: 'previous', next: 'next', play: 'play', pause: 'pause',
        zoom: 'zoom', download: 'download', of: '{n} / {total}',
        uploading: 'uploading {name}…', failed: '{name}: {message}'
    };

    const config = (window.kroomGallery ??= {});
    const strings = Object.assign({}, STOCK, config.strings);
    const t = (key, args = {}) => (strings[key] ?? key).replace(/\{(\w+)\}/g, (_, name) => args[name] ?? '');

    function element(tag, className, text) {
        const el = document.createElement(tag);
        if (className) el.className = className;
        if (text !== undefined) el.textContent = text;
        return el;
    }

    function button(className, glyph, word) {
        const b = element('button', className, glyph);
        b.type = 'button';
        b.title = t(word);
        b.setAttribute('aria-label', t(word));
        return b;
    }

    const figures = (gallery) => [...gallery.querySelectorAll(':scope > figure')];
    const galleries = new Map();      // id → .kroom-gallery
    const shows = new Map();          // id → its slideshow, built on first open
    let current = null;               // the show on screen

    // --- the slideshow ------------------------------------------------------------------------------

    function showOf(gallery) {
        const id = gallery.dataset.gallery;
        if (!shows.has(id)) shows.set(id, build(gallery));
        return shows.get(id);
    }

    /** One dialog per gallery, appended to <body>: showModal() lifts it into the top layer, controls included. */
    function build(gallery) {
        const show = { id: gallery.dataset.gallery, index: 0, timer: null };
        const dialog = show.dialog = element('dialog', 'kroom-gallery-show');
        const scroller = show.scroller = element('div', 'kroom-gallery-slides');
        show.items = figures(gallery).map(figure => {
            const link = figure.querySelector('a');
            const slide = element('figure', 'kroom-gallery-slide');
            const img = element('img');
            img.setAttribute('loading', 'lazy');
            img.src = link?.getAttribute('href') ?? '';
            img.alt = figure.querySelector('img')?.alt ?? '';
            slide.append(img, element('figcaption', '', figure.querySelector('figcaption')?.textContent ?? ''));
            scroller.append(slide);
            return { slide, full: link?.dataset.full || img.src };
        });
        const total = show.items.length;

        const close = button('kroom-gallery-close', '✕', 'close').on('click', () => hide(show));
        const prev = button('kroom-gallery-prev', '‹', 'previous').on('click', () => go(show, show.index - 1));
        const next = button('kroom-gallery-next', '›', 'next').on('click', () => go(show, show.index + 1));
        show.play = button('kroom-gallery-play', '▶', 'play').on('click', () => toggle(show));
        const zoom = button('kroom-gallery-zoom', '⌕', 'zoom').on('click', () => zoomIn(show));
        show.download = element('a', 'kroom-gallery-download', '⤓');
        show.download.title = t('download');
        show.download.setAttribute('aria-label', t('download'));

        if (total <= MAX_DOTS) {
            show.dots = element('div', 'kroom-gallery-dots');
            show.items.forEach((_, i) => {
                const dot = element('button', 'kroom-gallery-dot');
                dot.type = 'button';
                dot.title = t('of', { n: i + 1, total });
                dot.setAttribute('aria-label', dot.title);
                show.dots.append(dot.on('click', () => go(show, i)));
            });
        } else {
            show.counter = element('div', 'kroom-gallery-counter');
        }

        show.zoom = element('div', 'kroom-gallery-zoomed');
        show.zoom.hidden = true;
        show.zoom.append(element('img'));
        show.zoom.on('click', () => zoomOut(show));

        dialog.append(scroller, close, prev, next, show.play, zoom, show.download, show.dots || show.counter, show.zoom);
        dialog.on('click', e => { if (e.target === dialog) hide(show); });
        dialog.on('cancel', e => e.preventDefault());     // Escape is ours: it closes the zoom first
        document.body.append(dialog);
        observe(show);
        return show;
    }

    /** The scroller is the source of truth — a swipe moves it with no script in the loop — so watch it. */
    function observe(show) {
        if (!window.IntersectionObserver) return;
        const ratios = new Map();
        show.observer = new IntersectionObserver(entries => {
            entries.forEach(e => ratios.set(e.target, e.intersectionRatio));
            let best = null, top = 0;
            ratios.forEach((ratio, slide) => { if (ratio > top) { top = ratio; best = slide; } });
            if (!best || top < 0.5) return;               // mid-swipe, no slide owns the viewport yet
            const index = show.items.findIndex(item => item.slide === best);
            if (index >= 0 && index !== show.index) setCurrent(show, index, true);
        }, { root: show.scroller, threshold: [0, 0.25, 0.5, 0.75, 1] });
        show.items.forEach(item => show.observer.observe(item.slide));
    }

    /** Reflect the slide the scroller is on: every path — arrows, dots, keys, swipe — ends here. */
    function setCurrent(show, index, updateHash) {
        show.index = index;
        const total = show.items.length;
        if (show.dots) [...show.dots.children].forEach((dot, i) => dot.toggleClass('active', i === index));
        if (show.counter) show.counter.textContent = t('of', { n: index + 1, total });
        const full = show.items[index].full;
        show.download.href = full;
        show.download.download = decodeURIComponent(full.split('?')[0].split('/').pop() || '');
        if (updateHash && current === show) setHash(show, false);
    }

    function go(show, index, updateHash = true) {
        const total = show.items.length;
        if (!total) return;
        const target = ((index % total) + total) % total;
        show.scroller.scrollLeft = target * show.items[0].slide.offsetWidth;
        setCurrent(show, target, updateHash);              // don't wait for the observer: arrows must feel instant
    }

    function display(show, index, pushHash = true) {
        if (current && current !== show) hide(current, false);
        current = show;
        if (pushHash) setHash(show, true, index);          // one history entry, so Back closes the show
        document.body.addClass('kroom-showing');
        if (!show.dialog.open) {
            if (show.dialog.showModal) show.dialog.showModal();
            else show.dialog.open = true;
        }
        go(show, index, false);
    }

    function hide(show, dropHash = true) {
        if (show.timer) toggle(show);
        zoomOut(show);
        document.body.removeClass('kroom-showing');
        if (show.dialog.close) show.dialog.close();
        else show.dialog.open = false;
        if (current === show) current = null;
        if (dropHash) history.replaceState(null, '', location.pathname + location.search);
    }

    function toggle(show) {
        if (show.timer) {
            clearInterval(show.timer);
            show.timer = null;
        } else {
            show.timer = setInterval(() => go(show, show.index + 1), PLAY_DELAY);
        }
        show.play.textContent = show.timer ? '❚❚' : '▶';
        show.play.title = t(show.timer ? 'pause' : 'play');
        show.play.setAttribute('aria-label', show.play.title);
    }

    function zoomIn(show) {
        show.zoom.firstChild.src = show.items[show.index]?.full ?? '';
        show.zoom.hidden = false;
    }

    function zoomOut(show) {
        show.zoom.hidden = true;
    }

    // --- deep links: #<gallery>-<n> opens slide n, and Back closes the show --------------------------

    function setHash(show, push, index = show.index) {
        const url = `${location.pathname}${location.search}#${show.id}-${index + 1}`;
        if (push) history.pushState(null, '', url);
        else history.replaceState(null, '', url);
    }

    function syncFromHash() {
        const [, id, n] = /^#(.+)-(\d+)$/.exec(location.hash) || [];
        const gallery = galleries.get(id);
        if (gallery && +n >= 1) {
            const show = showOf(gallery);
            if (current === show) go(show, n - 1, false);
            else display(show, n - 1, false);
        } else if (current) {
            hide(current, false);
        }
    }

    document.addEventListener('keydown', e => {
        const show = current;
        if (!show) return;
        switch (e.key) {
            case 'ArrowLeft': go(show, show.index - 1); break;
            case 'ArrowRight': go(show, show.index + 1); break;
            case ' ': e.preventDefault(); toggle(show); break;
            case 'Escape':
                e.preventDefault();
                if (!show.zoom.hidden) zoomOut(show);
                else hide(show);
                break;
        }
    });

    // --- the editor ---------------------------------------------------------------------------------

    const itemsOf = (gallery) => figures(gallery).map(figure => ({
        media: figure.dataset.media, captions: JSON.parse(figure.dataset.captions || '{}')
    }));

    function status(gallery, text) {
        const box = gallery.previousElementSibling?.querySelector('.kroom-gallery-status');
        if (box) box.textContent = text || '';
    }

    /** JSON through api.js when the endpoint is under its base, so its headers and errors apply. */
    function put(url, body) {
        if (typeof api !== 'undefined' && api.putJson && url.startsWith(api.base)) {
            return api.putJson(url.slice(api.base.length), body);
        }
        return fetch(url, {
            method: 'PUT', credentials: 'same-origin', body: JSON.stringify(body),
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' }
        }).then(async resp => {
            const answer = await resp.json().catch(() => ({}));
            if (!resp.ok) throw new Error(answer.message || resp.statusText);
            return answer;
        });
    }

    /** The grid as it stands, written; the reply is the gallery as stored, and is what gets shown. */
    function save(gallery, items = itemsOf(gallery)) {
        return put(`${gallery.dataset.api}/${encodeURIComponent(gallery.dataset.gallery)}`, { items })
            .then(stored => { render(gallery, stored); return true; })
            .catch(err => {
                status(gallery, t('failed', { name: gallery.dataset.gallery, message: err.message }));
                return false;
            });
    }

    function render(gallery, stored) {
        figures(gallery).forEach(f => f.remove());
        (stored.items || []).forEach(item => {
            const figure = element('figure');
            figure.dataset.media = item.media;
            figure.dataset.captions = JSON.stringify(item.captions || {});
            const link = element('a');
            link.href = item.slide;
            link.dataset.full = item.full;
            const img = element('img');
            img.src = item.thumb;
            img.alt = item.caption || '';
            img.setAttribute('loading', 'lazy');
            link.append(img);
            figure.append(link, element('figcaption', '', item.caption || ''));
            gallery.append(figure);
            decorate(gallery, figure);
        });
        // the slideshow is rebuilt from the new grid on its next opening
        const show = shows.get(gallery.dataset.gallery);
        if (show) {
            if (current === show) hide(show);
            show.observer?.disconnect();
            show.dialog.remove();
            shows.delete(show.id);
        }
    }

    function decorate(gallery, figure) {
        if (!gallery.hasAttribute('data-edit') || figure.querySelector('.kroom-gallery-remove')) return;
        figure.append(button('kroom-gallery-remove', '✕', 'delete'));
        const caption = figure.querySelector('figcaption') || figure.appendChild(element('figcaption'));
        caption.dataset.placeholder = t('caption');
    }

    function editCaption(gallery, figure, caption) {
        if (caption.querySelector('input')) return;
        const lang = gallery.dataset.lang || config.language || 'en';
        const captions = JSON.parse(figure.dataset.captions || '{}');
        const shown = caption.textContent;
        const input = element('input');
        input.value = captions[lang] ?? '';
        input.placeholder = t('caption');
        caption.textContent = '';
        caption.append(input);
        input.focus();
        let done = false;
        const finish = (keep) => {
            if (done) return;
            done = true;
            const value = input.value.trim();
            caption.textContent = keep ? value : shown;
            if (!keep || value === (captions[lang] ?? '')) return;
            if (value) captions[lang] = value;
            else delete captions[lang];
            figure.dataset.captions = JSON.stringify(captions);
            save(gallery);
        };
        input.on('keydown', e => {
            if (e.key === 'Enter') { e.preventDefault(); finish(true); }
            else if (e.key === 'Escape') { e.preventDefault(); finish(false); }
        });
        input.on('blur', () => finish(true));
    }

    /** Each file to the media store as it is, then one write appending them all. */
    async function upload(gallery, files) {
        const names = [];
        let failure = '';
        for (const file of [...files].filter(f => f.type.startsWith('image/'))) {
            status(gallery, t('uploading', { name: file.name }));
            try {
                const resp = await fetch(`${gallery.dataset.media}?name=${encodeURIComponent(file.name)}`,
                    { method: 'POST', body: file, credentials: 'same-origin' });
                const answer = await resp.json().catch(() => ({}));
                if (!resp.ok) throw new Error(answer.message || resp.statusText);
                names.push(answer.name);
            } catch (err) {
                failure = t('failed', { name: file.name, message: err.message });
            }
        }
        status(gallery, failure);
        if (names.length) await save(gallery, itemsOf(gallery).concat(names.map(media => ({ media, captions: {} }))));
    }

    function edit(gallery) {
        const tools = element('div', 'kroom-gallery-tools');
        const input = element('input');
        input.type = 'file';
        input.multiple = true;
        input.accept = 'image/*';
        input.hidden = true;
        input.on('change', () => { upload(gallery, input.files); input.value = ''; });
        const add = element('button', 'kroom-gallery-add', t('add'));
        add.type = 'button';
        add.on('click', () => input.click());
        tools.append(add, element('span', 'kroom-gallery-drop', t('drop')), input, element('div', 'kroom-gallery-status'));
        gallery.before(tools);
        figures(gallery).forEach(figure => decorate(gallery, figure));

        // files only: Sortable's own drags carry none
        const carriesFiles = e => [...(e.dataTransfer?.types || [])].includes('Files');
        [tools, gallery].forEach(zone => {
            zone.on('dragover', e => {
                if (!carriesFiles(e)) return;
                e.preventDefault();
                gallery.addClass('kroom-gallery-over');
            });
            zone.on('dragleave', () => gallery.removeClass('kroom-gallery-over'));
            zone.on('drop', e => {
                gallery.removeClass('kroom-gallery-over');
                if (!e.dataTransfer?.files?.length) return;
                e.preventDefault();
                upload(gallery, e.dataTransfer.files);
            });
        });

        if (window.Sortable) {
            new Sortable(gallery, {
                animation: 150,
                draggable: 'figure',
                filter: '.kroom-gallery-remove, input',
                preventOnFilter: false,
                onStart: () => { gallery.dragging = true; },
                onEnd: evt => {
                    setTimeout(() => { gallery.dragging = false; });     // the click a drop may fire is no opening
                    if (evt.oldIndex !== evt.newIndex) save(gallery);
                }
            });
        }
    }

    // --- wiring -------------------------------------------------------------------------------------

    function enhance(gallery) {
        galleries.set(gallery.dataset.gallery, gallery);
        const editing = gallery.hasAttribute('data-edit');
        if (editing) edit(gallery);
        gallery.on('click', e => {
            const figure = e.target.closest('figure');
            if (!figure || figure.parentElement !== gallery) return;
            if (editing && e.target.closest('.kroom-gallery-remove')) {
                if (confirm(t('confirmDelete'))) { figure.remove(); save(gallery); }
                return;
            }
            if (editing && e.target.closest('figcaption')) return editCaption(gallery, figure, e.target.closest('figcaption'));
            if (!e.target.closest('a')) return;
            e.preventDefault();
            if (gallery.dragging) return;
            display(showOf(gallery), figures(gallery).indexOf(figure));
        });
    }

    config.sync = (gallery) => save(gallery);

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.kroom-gallery[data-gallery]').forEach(enhance);
        window.addEventListener('hashchange', syncFromHash);
        syncFromHash();                                    // a link straight to #<gallery>-37 opens there
    });

})();
