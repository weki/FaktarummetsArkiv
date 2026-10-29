/* Global GPU poller - air-gapped, no modules */
(function () {
    'use strict';

    const state = {
        subscribers: new Set(),
        data: null,
        history: [],
        pollMs: 2000,
        timer: null,
        failures: 0,
        stale: false,
        lastOkAt: null,
        fetching: false,
        inFlight: null
    };

    const MAX_HISTORY = 300;

    function fetchSettings() {
        return fetch('/api/settings', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (json) {
                if (!json) return;
                var raw;
                if (Array.isArray(json.settings)) {
                    for (var i = 0; i < json.settings.length; i++) {
                        if (json.settings[i].key === 'gpu.poll-interval-ms') {
                            raw = json.settings[i].value != null ? json.settings[i].value : json.settings[i].defaultValue;
                        }
                    }
                } else {
                    raw = json['gpu.poll-interval-ms'];
                }
                var v = parseInt(raw, 10);
                if (!isNaN(v) && v >= 1000 && v <= 10000) {
                    state.pollMs = v;
                }
            })
            .catch(function () { });
    }

    function pushHistory(d) {
        if (!d) return;
        var gpu = d.gpu || {};
        if (gpu.available) {
            var pt = { t: Date.now(), util: gpu.utilPct, mem: gpu.memUsedMiB };
            state.history.push(pt);
            if (state.history.length > MAX_HISTORY) {
                state.history.shift();
            }
        }
    }

    function notify() {
        var out = state.data ? JSON.parse(JSON.stringify(state.data)) : null;
        if (out) {
            out._stale = state.stale;
            out._history = state.history.slice();
        }
        state.subscribers.forEach(function (fn) {
            try { fn(out); } catch (e) { /* ignore */ }
        });
    }

    function schedule() {
        if (state.timer) {
            clearTimeout(state.timer);
            state.timer = null;
        }
        if (state.subscribers.size === 0) return;
        if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
        var ms = state.stale ? 10000 : state.pollMs;
        state.timer = setTimeout(tick, ms);
    }

    function tick() {
        if (state.subscribers.size === 0) return;
        if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
            schedule();
            return;
        }
        if (state.fetching) {
            schedule();
            return;
        }
        state.fetching = true;
        fetch('/api/system/gpu', { credentials: 'same-origin' })
            .then(function (r) {
                if (!r.ok) throw new Error('bad');
                return r.json();
            })
            .then(function (json) {
                state.fetching = false;
                state.failures = 0;
                state.stale = false;
                state.lastOkAt = Date.now();
                state.data = json;
                pushHistory(json);
                notify();
                schedule();
            })
            .catch(function () {
                state.fetching = false;
                state.failures++;
                if (state.failures >= 3) {
                    state.stale = true;
                }
                // keep last data if exists
                notify();
                schedule();
            });
    }

    if (typeof document !== 'undefined') {
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') {
                if (state.subscribers.size > 0 && !state.timer) {
                    tick();
                }
            } else {
                if (state.timer) {
                    clearTimeout(state.timer);
                    state.timer = null;
                }
            }
        });
    }

    window.GpuPoller = {
        subscribe: function (fn) {
            if (typeof fn !== 'function') return;
            state.subscribers.add(fn);
            fetchSettings().then(function () {
                if (state.data) {
                    var out = JSON.parse(JSON.stringify(state.data));
                    out._stale = state.stale;
                    out._history = state.history.slice();
                    try { fn(out); } catch (e) { }
                }
                if (!state.timer) {
                    tick();
                } else {
                    schedule();
                }
            });
        },
        unsubscribe: function (fn) {
            state.subscribers.delete(fn);
            if (state.subscribers.size === 0 && state.timer) {
                clearTimeout(state.timer);
                state.timer = null;
            }
        },
        getData: function () {
            if (!state.data) return null;
            var out = JSON.parse(JSON.stringify(state.data));
            out._stale = state.stale;
            out._history = state.history.slice();
            return out;
        },
        getHistory: function (n) {
            if (!n) return state.history.slice();
            return state.history.slice(-n);
        },
        reset: function () {
            if (state.timer) {
                clearTimeout(state.timer);
                state.timer = null;
            }
            state.subscribers.clear();
            state.data = null;
            state.history = [];
            state.failures = 0;
            state.stale = false;
        }
    };
})();
