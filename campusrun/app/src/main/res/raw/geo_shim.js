/*
 * 在 document-start 注入，替换 navigator.geolocation。
 * 模式由原生侧 GeoBridge 决定：
 *   real —— 原样转给浏览器定位（打卡阶段必须真实）
 *   sim  —— 每 1000 ms 从原生拿一个模板定位点，构造标准 GeolocationPosition 回调
 * 原生侧切换模式后调用 window.__runGeo.push()，立即推送一个点，避免等待下一拍。
 */
(function () {
    'use strict';

    if (window.__runGeo && window.__runGeo.installed) return;

    var nativeGeo = navigator.geolocation;
    var nextId = 1;
    var watches = {};

    function mode() {
        try {
            return (window.RunGeo && window.RunGeo.mode && window.RunGeo.mode()) || 'real';
        } catch (e) {
            return 'real';
        }
    }

    function simFix() {
        try {
            var raw = window.RunGeo && window.RunGeo.fix && window.RunGeo.fix();
            if (!raw) return null;
            var f = JSON.parse(raw);
            if (!f || typeof f.lat !== 'number' || typeof f.lon !== 'number') return null;
            return f;
        } catch (e) {
            return null;
        }
    }

    function toPosition(f) {
        return {
            coords: {
                latitude: f.lat,
                longitude: f.lon,
                accuracy: f.acc == null ? 5 : f.acc,
                altitude: f.alt == null ? 40 : f.alt,
                altitudeAccuracy: 10,
                heading: f.heading == null ? null : f.heading,
                speed: f.speed == null ? 0 : f.speed
            },
            timestamp: f.t || Date.now()
        };
    }

    function stopSim(w) {
        if (w.timer) {
            clearInterval(w.timer);
            w.timer = null;
        }
    }

    function emitSim(w) {
        var f = simFix();
        if (!f) return;
        try {
            w.success(toPosition(f));
        } catch (e) {
            /* 页面的回调异常不应该影响后续推送 */
        }
    }

    function startSim(w) {
        if (w.timer) return;
        emitSim(w);
        w.timer = setInterval(function () {
            if (mode() !== 'sim') {
                stopSim(w);
                startReal(w);
                return;
            }
            emitSim(w);
        }, 1000);
    }

    function startReal(w) {
        if (!nativeGeo || w.realId != null) return;
        try {
            w.realId = nativeGeo.watchPosition(
                function (p) {
                    if (mode() !== 'real') return;
                    try {
                        w.success(p);
                    } catch (e) { }
                },
                function (err) {
                    if (mode() !== 'real' || !w.error) return;
                    try {
                        w.error(err);
                    } catch (e) { }
                },
                w.options
            );
        } catch (e) {
            w.realId = null;
        }
    }

    function stopReal(w) {
        if (nativeGeo && w.realId != null) {
            try {
                nativeGeo.clearWatch(w.realId);
            } catch (e) { }
            w.realId = null;
        }
    }

    function getCurrentPosition(success, error, options) {
        if (mode() === 'sim') {
            var f = simFix();
            if (f) {
                setTimeout(function () {
                    try {
                        success(toPosition(f));
                    } catch (e) { }
                }, 0);
                return;
            }
        }
        if (!nativeGeo) {
            if (error) error({ code: 2, message: 'geolocation unavailable' });
            return;
        }
        nativeGeo.getCurrentPosition(success, error, options);
    }

    function watchPosition(success, error, options) {
        var id = nextId++;
        var w = { success: success, error: error, options: options, timer: null, realId: null };
        watches[id] = w;
        if (mode() === 'sim') startSim(w);
        else startReal(w);
        return id;
    }

    function clearWatch(id) {
        var w = watches[id];
        if (!w) return;
        stopSim(w);
        stopReal(w);
        delete watches[id];
    }

    function push() {
        var m = mode();
        for (var id in watches) {
            if (!Object.prototype.hasOwnProperty.call(watches, id)) continue;
            var w = watches[id];
            if (m === 'sim') {
                stopReal(w);
                startSim(w);
            } else {
                stopSim(w);
                startReal(w);
            }
        }
    }

    var shim = {
        getCurrentPosition: getCurrentPosition,
        watchPosition: watchPosition,
        clearWatch: clearWatch
    };

    try {
        Object.defineProperty(navigator, 'geolocation', {
            value: shim,
            configurable: true,
            enumerable: true
        });
    } catch (e) {
        try {
            navigator.geolocation = shim;
        } catch (e2) { }
    }

    window.__runGeo = {
        installed: true,
        push: push,
        mode: mode
    };
})();
