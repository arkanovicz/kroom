// Chifoumi Arena - Lobby JS: the login and the language; the room is arena.js's (the fold and the binder)

document.addEventListener('DOMContentLoaded', () => {
    api.getJson('session').then(session => { if (session.id) enter(); }).catch(() => {});
    $('#login-form').on('submit', (e) => {
        e.preventDefault();
        const name = $('#player-name').val().trim();
        if (name.length < 2) return;
        api.postJson('join', { name }).then(result => {
            if (result.success) enter();
        }).catch(err => alert('Error: ' + err.message));
    });
});

// Logged in: the lobby shows and the stream opens; the snapshot fills the page, `me` included
function enter() {
    $('#login-section').hide();
    $('#lobby-section').show();
    kroom.on('state', () => tracker.start());
    kroom.connect();
}

const tracker = new StatusTracker({
    onStatusChange: (status) => kroom.act('status', { status }).catch(() => {})
});

function switchLang(lang) {
    window.location.href = window.location.pathname.replace(/^\/[a-z]{2}\//, `/${lang}/`);
}
