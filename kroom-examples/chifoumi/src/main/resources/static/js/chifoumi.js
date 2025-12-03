// Chifoumi Arena - Lobby JS

let playerName = null;
let sseConnection = null;

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    checkSession();
    setupLoginForm();
    setupQueueButtons();
});

// Check if already logged in
function checkSession() {
    api.getJson('session').then(session => {
        if (session.name) {
            playerName = session.name;
            showLobby();
            connectSSE();
        }
    }).catch(() => {
        // Not logged in, show login form
    });
}

// Setup login form
function setupLoginForm() {
    $('#login-form').on('submit', (e) => {
        e.preventDefault();
        const name = $('#player-name').value.trim();
        if (name.length < 2) return;

        api.postJson('join', { name }).then(result => {
            if (result.success) {
                playerName = result.name;
                showLobby();
                connectSSE();
            }
        }).catch(err => {
            alert('Error: ' + err.message);
        });
    });
}

// Show lobby section
function showLobby() {
    $('#login-section').hide();
    $('#lobby-section').show();
    $('#player-display').text(playerName);
}

// Setup queue buttons
function setupQueueButtons() {
    $('#find-match-btn').on('click', () => {
        joinQueue();
    });

    $('#cancel-search-btn').on('click', () => {
        leaveQueue();
    });
}

// Join matchmaking queue
function joinQueue() {
    $('#queue-status').hide();
    $('#searching').show();

    api.postJson('queue/join', {}).then(result => {
        if (result.matched) {
            // Match found immediately, redirect to game
            goToGame();
        }
        // Otherwise, wait for SSE match event
    }).catch(err => {
        $('#queue-status').show();
        $('#searching').hide();
        alert('Error: ' + err.message);
    });
}

// Leave queue
function leaveQueue() {
    api.postJson('queue/leave', {}).then(() => {
        $('#queue-status').show();
        $('#searching').hide();
    });
}

// Connect to SSE
function connectSSE() {
    sseConnection = sse('/events').connect();

    sseConnection.on('open', () => {
        console.log('SSE connected');
    });

    sseConnection.on('error', (err) => {
        console.error('SSE error', err);
    });

    // Online users update
    sseConnection.onJson('connected', (data) => {
        $('#online-num').text(data.users.length);
    });

    // Match found
    sseConnection.onJson('match', (match) => {
        console.log('Match found:', match);
        goToGame();
    });

    // Queue position update
    sseConnection.onJson('queue', (data) => {
        console.log('Queue position:', data.position);
    });

    // Activity feed
    sseConnection.onJson('activity', (event) => {
        addActivityEvent(event);
    });

    // Stats update
    sseConnection.onJson('stats', (stats) => {
        updateStats(stats);
    });
}

// Add activity event to feed
function addActivityEvent(event) {
    const feed = $('#activity-feed');
    const empty = feed.find('.empty');
    if (empty) empty.remove();

    const li = document.createElement('li');
    const time = new Date(event.time).toLocaleTimeString();

    switch (event.type) {
        case 'joined':
            li.innerHTML = `<strong>${event.player}</strong> joined`;
            break;
        case 'disconnected':
            li.innerHTML = `<strong>${event.player}</strong> left`;
            break;
        case 'match_started':
            li.innerHTML = `<strong>${event.player1}</strong> vs <strong>${event.player2}</strong> started`;
            break;
        case 'match_ended':
            li.innerHTML = `<strong>${event.winner}</strong> beat <strong>${event.loser}</strong> (${event.score})`;
            break;
        default:
            li.innerHTML = JSON.stringify(event);
    }

    li.innerHTML += `<span class="time">${time}</span>`;

    // Add to top
    feed.insertBefore(li, feed.firstChild);

    // Keep only last 10
    while (feed.children.length > 10) {
        feed.removeChild(feed.lastChild);
    }
}

// Update stats display
function updateStats(stats) {
    $('#stat-matches').text(stats.matchesPlayed || 0);
    if (stats.moves) {
        $('#stat-rock').text(stats.moves.rock || 0);
        $('#stat-paper').text(stats.moves.paper || 0);
        $('#stat-scissors').text(stats.moves.scissors || 0);
        $('#stat-well').text(stats.moves.well || 0);
    }
}

// Navigate to game page
function goToGame() {
    const lang = document.documentElement.lang || 'en';
    window.location.href = `/${lang}/game.html`;
}

// Switch language
function switchLang(lang) {
    const path = window.location.pathname.replace(/^\/[a-z]{2}\//, `/${lang}/`);
    window.location.href = path;
}
