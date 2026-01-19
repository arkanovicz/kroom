// Kroom Push Notifications Client
// Requires: api.js

const Push = {
  // VAPID public key - set by server via /api/push/vapid
  vapidPublicKey: null,

  // Service worker registration
  swRegistration: null,

  // Check if push is supported
  isSupported() {
    return 'serviceWorker' in navigator &&
           'PushManager' in window &&
           'Notification' in window;
  },

  // Check if running as installed PWA
  isPWA() {
    return window.matchMedia('(display-mode: standalone)').matches ||
           window.navigator.standalone === true;
  },

  // Check if iOS
  isIOS() {
    return /iPad|iPhone|iPod/.test(navigator.userAgent) ||
           (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  },

  // Initialize push system
  async init() {
    if (!this.isSupported()) {
      console.log('[Push] Not supported in this browser');
      return false;
    }

    try {
      // Register service worker
      this.swRegistration = await navigator.serviceWorker.register('/sw.js');
      console.log('[Push] Service worker registered');

      // Fetch VAPID public key from server
      const response = await api.getJson('push/vapid');
      if (response.status && response.publicKey) {
        this.vapidPublicKey = response.publicKey;
      }

      return true;
    } catch (error) {
      console.error('[Push] Init failed:', error);
      return false;
    }
  },

  // Get current permission state
  getPermissionState() {
    return Notification.permission; // 'default', 'granted', 'denied'
  },

  // Check if already subscribed
  async isSubscribed() {
    if (!this.swRegistration) return false;

    const subscription = await this.swRegistration.pushManager.getSubscription();
    return subscription !== null;
  },

  // Request permission and subscribe
  async subscribe() {
    if (!this.isSupported()) {
      throw new Error('Push notifications not supported');
    }

    // iOS requires PWA installation first
    if (this.isIOS() && !this.isPWA()) {
      throw new Error('ios-not-installed');
    }

    // Request notification permission
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') {
      throw new Error('permission-denied');
    }

    if (!this.vapidPublicKey) {
      throw new Error('VAPID key not available');
    }

    // Convert VAPID key to Uint8Array
    const applicationServerKey = this.urlBase64ToUint8Array(this.vapidPublicKey);

    // Subscribe to push
    const subscription = await this.swRegistration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: applicationServerKey
    });

    console.log('[Push] Subscribed:', subscription.endpoint);

    // Send subscription to server
    const response = await api.postJson('push/subscribe', subscription.toJSON());

    if (!response.status) {
      throw new Error(response.message || 'Failed to save subscription');
    }

    return subscription;
  },

  // Unsubscribe from push
  async unsubscribe() {
    const subscription = await this.swRegistration?.pushManager.getSubscription();

    if (subscription) {
      await subscription.unsubscribe();

      // Notify server
      await api.deleteJson('push/subscribe');

      console.log('[Push] Unsubscribed');
    }

    return true;
  },

  // Convert base64 VAPID key to Uint8Array
  urlBase64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding)
      .replace(/-/g, '+')
      .replace(/_/g, '/');

    const rawData = window.atob(base64);
    const outputArray = new Uint8Array(rawData.length);

    for (let i = 0; i < rawData.length; ++i) {
      outputArray[i] = rawData.charCodeAt(i);
    }
    return outputArray;
  }
};
