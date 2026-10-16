// Chifoumi Arena - Game JS

const MOVE_EMOJI = {
    rock: '🪨',
    paper: '📄',
    scissors: '✂️',
    well: '🕳️'
};

let state = null;
let match = null;      // mine, kept once finished (the state drops it)
let note = null;       // what the opponent last did this round
let myMove = null;     // only my own tabs see it
let showing = false;   // a round result stays on screen for a while

document.addEventListener('DOMContentLoaded', () => {
    api.getJson('session').then(session => {
        if (!session.id) return goToLobby();
        state = initialState(session.id);
        intents.attach({ room: 'chifoumi-lobby', payload, store: { dispatch: render } });
        const conn = sse('/events');
        FRAMES.forEach(name => conn.onJson(name, data => {
            state = reduce(state, { event: name, data });
            react(name, data);
            render();
        }));
        conn.connect();
    }).catch(() => goToLobby());
});

const me = () => state.me;
const opponentName = () => match.player1 === me() ? match.name2 : match.name1;
const ours = (data) => match && data.match?.id === match.id;

function react(event, data) {
    const live = myMatch(state, me());
    if (event === 'state') {
        if (live) match = live;
        else if (!match?.finished) return goToLobby();
    }
    if (live && match && live.id !== match.id) return window.location.reload();   // played again
    switch (event) {
        case 'match':
            if (ours(data)) match = data.match;   // ended by the room: the opponent left
            break;
        case 'play':
            if (data.player === me()) myMove = data.move?.toLowerCase() ?? myMove;
            else if (match && has(match, data.player)) note = say('opponent-played', { name: opponentName() });
            break;
        case 'cancel':
            if (data.intent !== 'play') break;
            if (data.user === me()) myMove = null;
            else if (match && has(match, data.user)) note = say('took-back', { name: opponentName() });
            break;
        case 'round':
            if (ours(data)) showRound(data);
            break;
    }
}

function render() {
    if (!match) return;
    const first = match.player1 === me();
    $('#player1-name').text(first ? say('you') : match.name1);
    $('#player2-name').text(first ? match.name2 : say('you'));
    $('#player1-score').text(match.score1);
    $('#player2-score').text(match.score2);
    $('#round-num').text(match.round);
    $('#target').text(match.target);
    if (showing) return;
    if (match.finished) return renderResult();

    $('#round-result').hide();
    $('#move-buttons').show();
    const waiting = played(state);
    $$('.move-btn').forEach(btn => btn.disable(waiting).toggleClass('selected', waiting && btn.dataset.move === myMove));
    if (waiting) $('#take-back').show(); else $('#take-back').hide();
    $('#game-message').text(waiting ? say('waiting') : note || say('choose'));
}

function showRound({ match: current, round }) {
    match = current;
    note = null;
    myMove = null;
    showing = true;
    const first = match.player1 === me();
    $('#move-buttons').hide();
    $('#take-back').hide();
    $('#round-result').show();
    $('#game-message').text('');
    $('#your-move .emoji').text(MOVE_EMOJI[first ? round.move1 : round.move2]);
    $('#opponent-move .emoji').text(MOVE_EMOJI[first ? round.move2 : round.move1]);
    const outcome = round.winner === me() ? 'win' : round.winner ? 'lose' : 'draw';
    $('#result-indicator').removeClass('win lose draw').addClass(outcome).text(round.winner ? '✓' : '=');
    $('#round-message').text(say(`round-${outcome}`));
    $('#round-verdict').text(verdict(round));
    setTimeout(() => { showing = false; render(); }, 2000);
}

// Why the round went that way, winner's move first
function verdict(round) {
    const key = round.result === 0 ? 'draw'
        : round.result === 1 ? `${round.move1}-${round.move2}` : `${round.move2}-${round.move1}`;
    return document.querySelector(`#verdicts [data-verdict="${key}"]`)?.textContent || '';
}

function renderResult() {
    $('#move-buttons').hide();
    $('#take-back').hide();
    $('#round-result').hide();
    $('#game-message').text('');
    const won = match.winner === me();
    $('#match-result').show().removeClass('winner loser').addClass(won ? 'winner' : 'loser');
    $('#match-result-title').text(won ? `${say('victory')} 🎉` : `${say('defeat')} 😢`);
    $('#match-result-message').text(won ? say('defeated', { name: opponentName() }) : say('wins', { name: opponentName() }));
    if (searching(state)) { $('#play-again-btn').hide(); $('#again-searching').show(); }
    else { $('#play-again-btn').show(); $('#again-searching').hide(); }
}

function goToLobby() {
    const lang = document.documentElement.lang || 'en';
    window.location.href = `/${lang}/index.html`;
}
