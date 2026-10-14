// Chifoumi Arena - Game JS

let playerName = null;
let match = null;
let sseConnection = null;
let myMove = null;
let waitingForOpponent = false;

const MOVE_EMOJI = {
    rock: '🪨',
    paper: '📄',
    scissors: '✂️',
    well: '🕳️'
};

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    checkSession();
    setupMoveButtons();
});

// Check session and get match info
function checkSession() {
    api.getJson('session').then(session => {
        if (!session.name) {
            // Not logged in, redirect to lobby
            goToLobby();
            return;
        }
        playerName = session.name;

        // Get current match
        return api.getJson('match');
    }).then(data => {
        if (!data || !data.match) {
            // No active match, redirect to lobby
            goToLobby();
            return;
        }
        match = data.match;
        updateMatchDisplay();
        connectSSE();
    }).catch(err => {
        console.error('Error:', err);
        goToLobby();
    });
}

// The click itself is an intent (intents.js); here only the highlight of the chosen move
function setupMoveButtons() {
    $$('.move-btn').forEach(btn => {
        btn.on('click', () => {
            if (waitingForOpponent) return;
            myMove = btn.data('move');
            btn.addClass('selected');
        });
    });
}

// A move accepted: all buttons wait for the opponent
function movePlayed() {
    $$('.move-btn').forEach(btn => btn.disable(true));
    $('#game-message').text('Waiting for opponent...');
    waitingForOpponent = true;
}

// Connect to SSE for real-time updates
function connectSSE() {
    sseConnection = sse('/events').connect();
    intents.attach({ room: 'chifoumi-lobby', sse: sseConnection });

    sseConnection.on('open', () => {
        console.log('SSE connected');
    });

    // our own play, back with its verdict (the opponent never gets it)
    sseConnection.onJson('play', ({ _ }) => {
        if (_.tab !== api.tabId()) return;
        if (_.stage === 'APPLIED') movePlayed(); else resetMoveButtons();
    });

    // Play again: a new match reloads, a wait goes back to the lobby
    sseConnection.onJson('match', () => window.location.reload());
    sseConnection.onJson('queue', () => goToLobby());

    // Opponent is waiting
    sseConnection.onJson('waiting', (data) => {
        console.log('Opponent played');
        if (!waitingForOpponent) {
            $('#game-message').text(`${data.player} has played. Your turn!`);
        }
    });

    // Round complete
    sseConnection.onJson('round', showRoundResult);

    // Match complete
    sseConnection.onJson('result', (result) => {
        match = result.match;
        showMatchResult();
    });
}

// Update match display
function updateMatchDisplay() {
    const isPlayer1 = match.player1 === playerName;

    $('#player1-name').text(isPlayer1 ? 'You' : match.player1);
    $('#player2-name').text(isPlayer1 ? match.player2 : 'You');

    $('#player1-score').text(match.score1);
    $('#player2-score').text(match.score2);

    $('#round-num').text(match.round);
    $('#target').text(match.target);
}

// Why the round went that way, winner's move first (the page holds the phrases, translated)
function verdict(round) {
    const key = round.result === 0 ? 'draw'
        : round.result === 1 ? `${round.move1}-${round.move2}` : `${round.move2}-${round.move1}`;
    return $(`#verdicts [data-verdict="${key}"]`)?.text() || '';
}

// Show round result: the round that just ended and the match as the server counts it
function showRoundResult({ round, match: current }) {
    match = current;
    const isPlayer1 = match.player1 === playerName;
    const myMoveResult = isPlayer1 ? round.move1 : round.move2;
    const oppMoveResult = isPlayer1 ? round.move2 : round.move1;

    // Hide move buttons, show result
    $('#move-buttons').hide();
    $('#round-result').show();

    // Show moves
    $('#your-move .emoji').text(MOVE_EMOJI[myMoveResult]);
    $('#opponent-move .emoji').text(MOVE_EMOJI[oppMoveResult]);

    // Show result indicator
    const indicator = $('#result-indicator');
    let resultClass = 'draw';
    let message = 'Draw!';

    if (round.winner === playerName) {
        resultClass = 'win';
        message = 'You win this round!';
    } else if (round.winner) {
        resultClass = 'lose';
        message = 'You lose this round!';
    }

    indicator.removeClass('win lose draw').addClass(resultClass);
    indicator.text(round.winner ? '✓' : '=');
    $('#round-message').text(message);
    $('#round-verdict').text(verdict(round));

    $('#player1-score').text(match.score1);
    $('#player2-score').text(match.score2);

    // Auto-advance to next round after delay
    setTimeout(() => {
        if (!match.finished) {
            nextRound();
        }
    }, 2000);
}

// Prepare for next round
function nextRound() {
    myMove = null;
    waitingForOpponent = false;

    $('#round-num').text(match.round);

    // Reset UI
    $('#round-result').hide();
    $('#move-buttons').show();
    $('#game-message').text('Choose your move!');
    resetMoveButtons();
}

// Reset move buttons
function resetMoveButtons() {
    $$('.move-btn').forEach(btn => {
        btn.disable(false);
        btn.removeClass('selected');
    });
    waitingForOpponent = false;
}

// Show match result
function showMatchResult() {
    $('#move-buttons').hide();
    $('#round-result').hide();
    $('#match-result').show();

    const won = match.winner === playerName;
    const resultDiv = $('#match-result');

    if (won) {
        resultDiv.addClass('winner');
        $('#match-result-title').text('Victory! 🎉');
        $('#match-result-message').text(`You defeated ${match.winner === match.player1 ? match.player2 : match.player1}!`);
    } else {
        resultDiv.addClass('loser');
        $('#match-result-title').text('Defeat 😢');
        $('#match-result-message').text(`${match.winner} wins. Better luck next time!`);
    }
}

// Go back to lobby
function goToLobby() {
    const lang = document.documentElement.lang || 'en';
    window.location.href = `/${lang}/index.html`;
}
