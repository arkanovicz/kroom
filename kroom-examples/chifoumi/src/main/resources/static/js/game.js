// Chifoumi Arena - Game JS: a logged-in viewer opens the stream; the match is arena.js's (the fold, the binder, the perspective)

document.addEventListener('DOMContentLoaded', () => {
    api.getJson('session').then(session => { if (session.id) kroom.connect(); else goToLobby(); }).catch(goToLobby);
});

function goToLobby() {
    window.location.href = `/${document.documentElement.lang || 'en'}/index.html`;
}
