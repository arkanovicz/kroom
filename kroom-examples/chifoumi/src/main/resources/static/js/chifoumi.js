// Chifoumi Arena - Lobby JS

const ROOM = 'chifoumi-lobby';
let state = null;

document.addEventListener('DOMContentLoaded', () => {
    api.getJson('session').then(session => { if (session.id) enter(session); }).catch(() => {});
    $('#login-form').on('submit', (e) => {
        e.preventDefault();
        const name = $('#player-name').val().trim();
        if (name.length < 2) return;
        api.postJson('join', { name }).then(result => {
            if (result.success) enter(result);
        }).catch(err => alert('Error: ' + err.message));
    });
});

function enter(session) {
    $('#login-section').hide();
    $('#lobby-section').show();
    $('#player-display').text(session.name);
    state = initialState(session.id);

    intents.attach({ room: ROOM, payload, store: { dispatch: ({ type, payload: verdict }) => {
        if (verdict.intent === 'chat' && type === 'accepted') $('#chat-form [name="text"]').val('');
        render();
    } } });

    const conn = sse('/events');
    FRAMES.forEach(name => conn.onJson(name, data => {
        state = reduce(state, { event: name, data });
        const line = activity(name, data);
        if (line) addActivity(line);
        render();
    }));
    conn.onJson('state', () => tracker.start());
    conn.connect();
}

const tracker = new StatusTracker({
    onStatusChange: (status) => api.act(ROOM, 'status', { status }).catch(() => {})
});

function render() {
    if (myMatch(state, state.me)) return goToGame();
    $('#online-num').text(state.users.length);
    $('#queue-count').text(state.queue.length ? say('waiting', { n: state.queue.length }) : '');
    if (searching(state)) { $('#queue-status').hide(); $('#searching').show(); }
    else { $('#queue-status').show(); $('#searching').hide(); }
    renderChat();
    renderStats();
}

function renderChat() {
    const log = $('#chat-log').clear();
    state.history.forEach(line => {
        const li = document.createElement('li');
        li.textContent = `${line.author?.name ?? line.from}: ${line.text}`;
        log.append(li);
    });
    log.scrollTop = log.scrollHeight;
}

function renderStats() {
    const stats = state.stats;
    $('#stat-matches').text(stats.played || 0);
    ['rock', 'paper', 'scissors', 'well'].forEach(move => $(`#stat-${move}`).text(stats[move] || 0));
}

// The feed is what the frames tell, as they come: the snapshot has no past
function activity(event, data) {
    switch (event) {
        case 'join': return say('joined', { name: data.user.name });
        case 'leave': return say('left', { name: data.user.name });
        case 'match':
            if (data.match?.finished) return ended(data.match);
            return data.match && data.player ? say('started', { a: data.match.name1, b: data.match.name2 }) : null;
        case 'round': return data.match.finished ? ended(data.match) : null;
        default: return null;
    }
}

function ended(m) {
    const first = m.winner === m.player1;
    return say('beat', {
        winner: first ? m.name1 : m.name2,
        loser: first ? m.name2 : m.name1,
        score: first ? `${m.score1}-${m.score2}` : `${m.score2}-${m.score1}`
    });
}

function addActivity(text) {
    const feed = $('#activity-feed');
    feed.querySelector('.empty')?.remove();
    const li = document.createElement('li');
    li.textContent = text;
    const time = document.createElement('span');
    time.className = 'time';
    time.textContent = new Date().toLocaleTimeString();
    li.append(time);
    feed.prepend(li);
    while (feed.children.length > 10) feed.lastChild.remove();
}

function goToGame() {
    const lang = document.documentElement.lang || 'en';
    window.location.href = `/${lang}/game.html`;
}

function switchLang(lang) {
    window.location.href = window.location.pathname.replace(/^\/[a-z]{2}\//, `/${lang}/`);
}
