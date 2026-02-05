// status.js - Player status tracking for kroom
// Detects idle/away states and sends status updates to server

const PlayerStatus = {
    ONLINE: 'online',
    IDLE: 'idle',
    AWAY: 'away',
    OFFLINE: 'offline'
};

/**
 * Player status tracker.
 * Monitors user activity and visibility to detect idle/away states.
 *
 * Usage:
 *   const statusTracker = new StatusTracker({
 *       idleTimeout: 120000,  // 2 minutes
 *       onStatusChange: (status) => api.postJson('game/123/status', { status })
 *   });
 *   statusTracker.start();
 */
class StatusTracker {
    constructor(options = {}) {
        this.idleTimeout = options.idleTimeout || 120000;  // 2 min default
        this.onStatusChange = options.onStatusChange || (() => {});
        this.currentStatus = PlayerStatus.ONLINE;
        this.idleTimer = null;
        this.activityEvents = ['mousedown', 'mousemove', 'keydown', 'touchstart', 'scroll'];
        this.boundResetIdle = this.resetIdle.bind(this);
        this.boundHandleVisibility = this.handleVisibility.bind(this);
        this.running = false;
    }

    start() {
        if (this.running) return;
        this.running = true;

        // Track visibility changes
        document.addEventListener('visibilitychange', this.boundHandleVisibility);

        // Track user activity
        this.activityEvents.forEach(event => {
            document.addEventListener(event, this.boundResetIdle, { passive: true });
        });

        // Start idle timer
        this.resetIdle();

        // Set initial status based on visibility
        if (document.visibilityState === 'hidden') {
            this.setStatus(PlayerStatus.AWAY);
        }
    }

    stop() {
        if (!this.running) return;
        this.running = false;

        document.removeEventListener('visibilitychange', this.boundHandleVisibility);
        this.activityEvents.forEach(event => {
            document.removeEventListener(event, this.boundResetIdle);
        });

        if (this.idleTimer) {
            clearTimeout(this.idleTimer);
            this.idleTimer = null;
        }
    }

    handleVisibility() {
        if (document.visibilityState === 'hidden') {
            this.setStatus(PlayerStatus.AWAY);
        } else {
            // Tab visible again - restore to ONLINE (activity will reset idle timer)
            this.setStatus(PlayerStatus.ONLINE);
            this.resetIdle();
        }
    }

    resetIdle() {
        // Only reset if visible (away takes priority)
        if (document.visibilityState === 'hidden') return;

        // Clear existing timer
        if (this.idleTimer) {
            clearTimeout(this.idleTimer);
        }

        // If currently idle, go back to online
        if (this.currentStatus === PlayerStatus.IDLE) {
            this.setStatus(PlayerStatus.ONLINE);
        }

        // Start new idle timer
        this.idleTimer = setTimeout(() => {
            if (document.visibilityState !== 'hidden') {
                this.setStatus(PlayerStatus.IDLE);
            }
        }, this.idleTimeout);
    }

    setStatus(newStatus) {
        if (this.currentStatus === newStatus) return;
        const oldStatus = this.currentStatus;
        this.currentStatus = newStatus;
        console.log(`Player status: ${oldStatus} -> ${newStatus}`);
        this.onStatusChange(newStatus, oldStatus);
    }

    getStatus() {
        return this.currentStatus;
    }
}

// Export for both module and global use
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { PlayerStatus, StatusTracker };
}
