// Kroom Service Worker - Push Notifications
// Configure by setting window.kroomPushConfig before loading

const CACHE_NAME = 'kroom-v1';

// Install event
self.addEventListener('install', event => {
  console.log('[SW] Installing service worker');
  self.skipWaiting();
});

// Activate event
self.addEventListener('activate', event => {
  console.log('[SW] Activating service worker');
  event.waitUntil(clients.claim());
});

// Push event - show notification
self.addEventListener('push', event => {
  console.log('[SW] Push received');

  let data = { title: 'Notification', body: 'New notification', url: '/' };

  if (event.data) {
    try {
      data = event.data.json();
    } catch (e) {
      data.body = event.data.text();
    }
  }

  const options = {
    body: data.body,
    icon: data.icon || '/web-app-manifest-192x192.png',
    badge: data.badge || '/favicon-96x96.png',
    tag: data.tag || 'kroom-notification',
    renotify: true,
    data: { url: data.url || '/' },
    vibrate: [200, 100, 200]
  };

  event.waitUntil(
    self.registration.showNotification(data.title || 'Notification', options)
  );
});

// Notification click - open app at specified URL
self.addEventListener('notificationclick', event => {
  console.log('[SW] Notification clicked');
  event.notification.close();

  const url = event.notification.data?.url || '/';

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true })
      .then(windowClients => {
        // Check if app is already open
        for (const client of windowClients) {
          if (client.url.includes(self.location.origin) && 'focus' in client) {
            client.navigate(url);
            return client.focus();
          }
        }
        // Open new window if not
        if (clients.openWindow) {
          return clients.openWindow(url);
        }
      })
  );
});

// Push subscription change - re-subscribe
self.addEventListener('pushsubscriptionchange', event => {
  console.log('[SW] Push subscription changed');
  event.waitUntil(
    self.registration.pushManager.subscribe(event.oldSubscription.options)
      .then(subscription => {
        // Send new subscription to server
        return fetch('/api/push/subscribe', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(subscription.toJSON())
        });
      })
  );
});
