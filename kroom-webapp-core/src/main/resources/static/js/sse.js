// SSE (Server-Sent Events) helper

class SSEConnection {
  constructor(url, options = {}) {
    this.url = url;
    this.reconnectDelay = options.reconnectDelay || 5000;
    this.handlers = {};
    this.eventSource = null;
    this.connected = false;
  }

  connect() {
    if (this.eventSource) {
      this.disconnect();
    }

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

      // Auto-reconnect
      if (this.eventSource.readyState === EventSource.CLOSED) {
        console.log(`SSE reconnecting in ${this.reconnectDelay}ms...`);
        setTimeout(() => this.connect(), this.reconnectDelay);
      }
    };

    // Handle named events
    this.eventSource.onmessage = (event) => {
      this.emit('message', event.data);
    };

    return this;
  }

  disconnect() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
      this.connected = false;
      console.log('SSE disconnected');
    }
    return this;
  }

  on(eventName, handler) {
    if (!this.handlers[eventName]) {
      this.handlers[eventName] = [];

      // Register with EventSource for custom events
      if (this.eventSource && !['open', 'error', 'message'].includes(eventName)) {
        this.eventSource.addEventListener(eventName, (event) => {
          this.emit(eventName, event.data);
        });
      }
    }
    this.handlers[eventName].push(handler);

    // If already connected and registering a new event type, add listener
    if (this.eventSource && !['open', 'error', 'message'].includes(eventName)) {
      this.eventSource.addEventListener(eventName, (event) => {
        handler(event.data);
      });
    }

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

  // Parse JSON data helper
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
}

// Factory function
function sse(url, options) {
  return new SSEConnection(url, options);
}
