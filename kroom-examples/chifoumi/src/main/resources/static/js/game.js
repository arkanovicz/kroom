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
    setupPlayAgain();
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

// Setup move buttons
function setupMoveButtons() {
    $$('.move-btn').forEach(btn => {
        btn.on('click', () => {
            if (waitingForOpponent) return;
            const move = btn.data('move');
            playMove(move);
        });
    });
}

// Setup play again button
function setupPlayAgain() {
    $('#play-again-btn').on('click', () => {
        // Join queue again
        api.postJson('queue/join', {}).then(result => {
            if (result.matched) {
                // New match, reload page
                window.location.reload();
            } else {
                // Waiting for opponent, go to lobby
                goToLobby();
            }
        });
    });
}

// Play a move
function playMove(move) {
    if (waitingForOpponent) return;

    myMove = move;

    // Disable buttons and show selection
    $$('.move-btn').forEach(btn => {
        btn.disable(true);
        if (btn.data('move') === move) {
            btn.addClass('selected');
        }
    });

    $('#game-message').text('Waiting for opponent...');
    waitingForOpponent = true;

    api.postJson('play', { move }).then(result => {
        if (result.waiting) {
            // Still waiting for opponent
            return;
        }

        if (result.round) {
            // Round complete, will be handled by SSE
        }
    }).catch(err => {
        console.error('Error:', err);
        resetMoveButtons();
        $('#game-message').text('Error: ' + err.message);
    });
}

// Connect to SSE for real-time updates
function connectSSE() {
    sseConnection = sse('/events').connect();

    sseConnection.on('open', () => {
        console.log('SSE connected');
    });

    // Opponent is waiting
    sseConnection.onJson('waiting', (data) => {
        console.log('Opponent played');
        if (!waitingForOpponent) {
            $('#game-message').text(`${data.player} has played. Your turn!`);
        }
    });

    // Round complete
    sseConnection.onJson('round', (round) => {
        showRoundResult(round);
    });

    // Match complete
    sseConnection.onJson('result', (result) => {
        match = result;
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
}

// Show round result
function showRoundResult(round) {
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

    // Update scores
    if (round.result !== 0) {
        const isP1Win = round.result === 1;
        const newScore1 = parseInt($('#player1-score').text()) + (isP1Win ? 1 : 0);
        const newScore2 = parseInt($('#player2-score').text()) + (isP1Win ? 0 : 1);
        $('#player1-score').text(newScore1);
        $('#player2-score').text(newScore2);
    }

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

    // Update round number
    const roundNum = parseInt($('#round-num').text()) + 1;
    $('#round-num').text(roundNum);

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
