// The lobby's state, folded from its frames the way the room folds it (LobbyState.reduce), plus
// what the room adds for the viewer: presence (`users`) and the intents it holds (`pending`).

const FRAMES = ['state', 'join', 'leave', 'status', 'match', 'play', 'round', 'cancel', 'chat'];

function initialState(me) {
    return { me, history: [], queue: [], matches: {}, stats: {}, users: [], pending: [] };
}

const has = (match, user) => match.player1 === user || match.player2 === user;

function reduce(state, { event, data }) {
    const me = state.me;
    switch (event) {
        case 'state':   // the snapshot leaves out what is empty; `me` is the viewer's presence entry, the page keeps the id
            return { ...initialState(me), ...data, me: data.me?.id ?? me };
        case 'join': {
            const i = state.users.findIndex(u => u.id === data.user.id);
            if (i < 0) state.users.push(data.user); else state.users[i] = data.user;
            break;
        }
        case 'leave':
            state.users = state.users.filter(u => u.id !== data.user.id);
            state.queue = state.queue.filter(id => id !== data.user.id);
            break;
        case 'status': {
            const user = state.users.find(u => u.id === data.user);
            if (user) user.status = data.status;
            break;
        }
        case 'match':
            if (data.match) {
                record(state, data.match);
                state.queue = state.queue.filter(id => !has(data.match, id));
                if (has(data.match, me)) release(state, 'match');
            } else if (data.player) {
                state.queue.push(data.player);
                if (data.player === me) hold(state, 'match');   // framed alone = held
            }
            break;
        case 'play':
            if (data.player === me) hold(state, 'play');
            break;
        case 'round':
            record(state, data.match);
            count(state.stats, data.round);
            if (has(data.match, me)) release(state, 'play');
            break;
        case 'cancel':
            if (data.intent === 'match') state.queue = state.queue.filter(id => id !== data.user);
            if (data.user === me) release(state, data.intent);
            break;
        case 'chat':
            state.history = [...state.history, data].slice(-50);
            break;
    }
    return state;
}

function record(state, match) {
    if (match.finished) { delete state.matches[match.id]; state.stats.played = (state.stats.played || 0) + 1; }   // won or forfeited
    else state.matches[match.id] = match;
}

function count(stats, round) {
    [round.move1, round.move2].forEach(move => {
        if (['rock', 'paper', 'scissors', 'well'].includes(move)) stats[move] = (stats[move] || 0) + 1;
    });
}

const hold = (state, intent) => { if (!state.pending.includes(intent)) state.pending.push(intent); };
const release = (state, intent) => { state.pending = state.pending.filter(i => i !== intent); };

const myMatch = (state, me) => Object.values(state.matches).find(m => !m.finished && has(m, me)) || null;
const searching = (state) => state.pending.includes('match');
const played = (state) => state.pending.includes('play');

// Both pages: a trigger in a form sends the form's fields (chat), a cancel names what it withdraws.
function payload(el) {
    const { intent, intentOf, ...rest } = el.dataset;
    if (intent === 'cancel') return { intent: intentOf };
    return el.form ? { ...rest, ...Object.fromEntries(new FormData(el.form)) } : rest;
}

// A sentence held by the template, so that l10n translates it; `{name}` placeholders filled in.
function say(key, vars = {}) {
    const el = document.querySelector(`#messages [data-msg="${key}"]`);
    return (el ? el.textContent : key).replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? '');
}
