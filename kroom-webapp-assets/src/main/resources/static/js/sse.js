// sse.js - SSE client with platform abstraction
// Part of kroom-webapp-assets
//
// Browser: uses native EventSource
// Native WebView: delegates to window.kroomSSE if provided by the app

/**
 * SSE connection with automatic reconnection and platform abstraction.
 *
 * Usage:
 *   const conn = sse('/events/room?login=user')
 *       .onJson('state', data => store.dispatch({ type: 'STATE', payload: data }))
 *       .on('error', () => showOffline())
 *       .connect();
 *
 * Native apps should set window.kroomSSE before loading the app:
 *   window.kroomSSE = {
 *       connect(url, callbacks) { ... },  // callbacks: { onOpen, onError, onEvent(name, data) }
 *       disconnect() { ... }
 *   };
 */
class SSEConnection {
    constructor(url, options = {}) {
        this.url = url;
        this.reconnectDelay = options.reconnectDelay || 5000;
        this.handlers = {};
        this.eventSource = null;
        this.connected = false;
        this.provider = window.kroomSSE || null;
    }

    connect() {
        if (this.eventSource) {
            this.disconnect();
        }

        if (this.provider) {
            // Native bridge - delegate to app
            this.provider.connect(this.url, {
                onOpen: () => {
                    this.connected = true;
                    console.log(`SSE connected to ${this.url} (native)`);
                    this.emit('open');
                },
                onError: (err) => {
                    console.error('SSE error (native):', err);
                    this.connected = false;
                    this.emit('error', err);
                },
                onEvent: (name, data) => {
                    this.emit(name, data);
                }
            });
        } else {
            // Browser - use EventSource
            this.eventSource = new EventSource(this.url);

            this.eventSource.onopen = () => {
                this.connected = true;
                console.log(`SSE connected to ${this.url}`);
                this.emit('open');
            };

            this.eventSource.onerror = (err) => {
                console.error('SSE error:', err);
                this.connected = false;
                this.emit('error', err);

                // EventSource auto-reconnects, but if closed we reconnect manually
                if (this.eventSource.readyState === EventSource.CLOSED) {
                    console.log(`SSE reconnecting in ${this.reconnectDelay}ms...`);
                    setTimeout(() => this.connect(), this.reconnectDelay);
                }
            };

            // Default message handler (unnamed events)
            this.eventSource.onmessage = (event) => {
                this.emit('message', event.data);
            };

            // bind handlers registered before connect() (the .onJson(...).connect() pattern)
            Object.keys(this.handlers).forEach((name) => this._bind(name));
        }

        return this;
    }

    // one EventSource listener per custom event, fanning out to all its handlers
    _bind(eventName) {
        if (this.eventSource && !['open', 'error', 'message'].includes(eventName)) {
            this.eventSource.addEventListener(eventName, (event) => this.emit(eventName, event.data));
        }
    }

    disconnect() {
        if (this.provider) {
            this.provider.disconnect();
        } else if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
        this.connected = false;
        console.log('SSE disconnected');
        return this;
    }

    on(eventName, handler) {
        const isNew = !this.handlers[eventName];
        if (isNew) this.handlers[eventName] = [];
        this.handlers[eventName].push(handler);
        // bind the EventSource listener once per event (when already connected);
        // pre-connect registrations are bound by connect()
        if (isNew) this._bind(eventName);
        return this;
    }

    off(eventName, handler) {
        if (this.handlers[eventName]) {
            if (handler) {
                this.handlers[eventName] = this.handlers[eventName].filter(h => h !== handler);
            } else {
                delete this.handlers[eventName];
            }
        }
        return this;
    }

    emit(eventName, data) {
        const handlers = this.handlers[eventName];
        if (handlers) {
            handlers.forEach(handler => {
                try {
                    handler(data);
                } catch (err) {
                    console.error(`SSE handler error for ${eventName}:`, err);
                }
            });
        }
    }

    /**
     * Register handler that auto-parses JSON data
     */
    onJson(eventName, handler) {
        return this.on(eventName, (data) => {
            try {
                const json = JSON.parse(data);
                handler(json);
            } catch (err) {
                console.error(`Failed to parse JSON for ${eventName}:`, err);
            }
        });
    }

    isConnected() {
        return this.connected;
    }
}

/**
 * Factory function
 */
function sse(url, options) {
    return new SSEConnection(url, options);
}
