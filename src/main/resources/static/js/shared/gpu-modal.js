(function () {
    'use strict';

    var modal = null;
    var backdrop = null;
    var content = null;
    var lastFocus = null;
    var pollerSub = null;
    var range = 60;
    var lastData = null;
    var historyCache = {};
    var isOpen = false;
    var pollTimer = null;

    function loadCss() {
        if (document.getElementById('gpuModalCss')) return;
        var link = document.createElement('link');
        link.id = 'gpuModalCss';
        link.rel = 'stylesheet';
        link.href = 'css/gpu-modal.css';
        document.head.appendChild(link);
    }

    function createModal() {
        if (modal) return;
        loadCss();
        backdrop = document.createElement('div');
        backdrop.className = 'gpu-modal-backdrop';
        backdrop.setAttribute('aria-hidden', 'true');

        modal = document.createElement('div');
        modal.className = 'gpu-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-labelledby', 'gpuModalTitle');
        modal.tabIndex = -1;

        var header = document.createElement('header');
        header.className = 'gpu-modal-header';
        var titleWrap = document.createElement('div');
        var title = document.createElement('h2');
        title.id = 'gpuModalTitle';
        title.textContent = 'GPU och modeller';
        var sub = document.createElement('div');
        sub.className = 'gpu-modal-sub';
        sub.id = 'gpuModalSub';
        titleWrap.appendChild(title);
        titleWrap.appendChild(sub);
        var actions = document.createElement('div');
        actions.className = 'gpu-modal-head-actions';
        var live = document.createElement('div');
        live.className = 'gpu-modal-live';
        live.id = 'gpuModalLive';
        var liveDot = document.createElement('span');
        liveDot.className = 'gpu-modal-live-dot';
        var liveText = document.createElement('span');
        liveText.id = 'gpuModalLiveText';
        liveText.textContent = 'Live';
        live.appendChild(liveDot);
        live.appendChild(liveText);
        var rangeWrap = document.createElement('div');
        rangeWrap.className = 'gpu-modal-range';
        var r1 = document.createElement('button');
        r1.type = 'button';
        r1.textContent = '5 min';
        r1.setAttribute('data-min', '5');
        var r2 = document.createElement('button');
        r2.type = 'button';
        r2.className = 'active';
        r2.textContent = '30 min';
        r2.setAttribute('data-min', '30');
        var r3 = document.createElement('button');
        r3.type = 'button';
        r3.textContent = '2 h';
        r3.setAttribute('data-min', '120');
        var r4 = document.createElement('button');
        r4.type = 'button';
        r4.textContent = '24 h';
        r4.setAttribute('data-min', '1440');
        rangeWrap.appendChild(r1);
        rangeWrap.appendChild(r2);
        rangeWrap.appendChild(r3);
        rangeWrap.appendChild(r4);
        [r1, r2, r3, r4].forEach(function (b) {
            b.addEventListener('click', function () {
                [r1, r2, r3, r4].forEach(function (x) { x.classList.remove('active'); });
                b.classList.add('active');
                var m = parseInt(b.getAttribute('data-min'), 10);
                try { localStorage.setItem('gpuModalRange', String(m)); } catch (e) { }
                range = m;
                loadHistory(range);
            });
        });
        try {
            var saved = localStorage.getItem('gpuModalRange');
            if (saved) {
                var sm = parseInt(saved, 10);
                if ([5, 30, 120, 1440].indexOf(sm) >= 0) {
                    range = sm;
                    [r1, r2, r3, r4].forEach(function (x) { x.classList.toggle('active', parseInt(x.getAttribute('data-min'), 10) === sm); });
                }
            }
        } catch (e) { }
        var close = document.createElement('button');
        close.type = 'button';
        close.className = 'gpu-modal-close';
        close.setAttribute('aria-label', 'Stäng');
        close.textContent = '×';
        close.addEventListener('click', closeModal);
        actions.appendChild(live);
        actions.appendChild(rangeWrap);
        actions.appendChild(close);
        header.appendChild(titleWrap);
        header.appendChild(actions);

        content = document.createElement('div');
        content.className = 'gpu-modal-content';

        modal.appendChild(header);
        modal.appendChild(content);
        document.body.appendChild(backdrop);
        document.body.appendChild(modal);

        backdrop.addEventListener('click', closeModal);
        document.addEventListener('keydown', onKey);
    }

    function onKey(e) {
        if (!isOpen) {
            if (e.key === 'g' && !isTyping(e.target)) {
                e.preventDefault();
                openModal();
            }
            return;
        }
        if (e.key === 'Escape') {
            e.preventDefault();
            closeModal();
            return;
        }
        trapFocus(e);
    }

    function isTyping(el) {
        if (!el) return false;
        var tag = el.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
        if (el.isContentEditable) return true;
        return false;
    }

    function trapFocus(e) {
        if (e.key !== 'Tab') return;
        var focusable = modal.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
        if (focusable.length === 0) return;
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (e.shiftKey) {
            if (document.activeElement === first) {
                last.focus();
                e.preventDefault();
            }
        } else {
            if (document.activeElement === last) {
                first.focus();
                e.preventDefault();
            }
        }
    }

    function openModal() {
        createModal();
        lastFocus = document.activeElement;
        isOpen = true;
        modal.classList.add('open');
        backdrop.classList.add('open');
        document.body.style.overflow = 'hidden';
        setTimeout(function () { modal.focus(); }, 10);
        buildContent();
        loadHistory(range);
        startPolling();
        if (typeof window.GpuPoller !== 'undefined') {
            if (pollerSub) {
                try { window.GpuPoller.unsubscribe(pollerSub); } catch (e) { }
            }
            pollerSub = function (data) {
                lastData = data;
                renderLive(data);
                mergeLive(data);
            };
            window.GpuPoller.subscribe(pollerSub);
        }
    }

    function closeModal() {
        isOpen = false;
        modal.classList.remove('open');
        backdrop.classList.remove('open');
        document.body.style.overflow = '';
        stopPolling();
        if (typeof window.GpuPoller !== 'undefined' && pollerSub) {
            try { window.GpuPoller.unsubscribe(pollerSub); } catch (e) { }
            pollerSub = null;
        }
        if (lastFocus && typeof lastFocus.focus === 'function') {
            try { lastFocus.focus(); } catch (e) { }
        }
    }

    function startPolling() {
        stopPolling();
        pollTimer = setInterval(function () {
            // rely on GpuPoller
        }, 2000);
    }

    function stopPolling() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function buildContent() {
        content.innerHTML = '';
        var liveEl = document.getElementById('gpuModalLive');
        var liveTextEl = document.getElementById('gpuModalLiveText');
        var subEl = document.getElementById('gpuModalSub');

        var banner = document.createElement('div');
        banner.className = 'gpu-modal-banner';
        banner.id = 'gpuModalBanner';
        banner.style.display = 'none';
        var bannerText = document.createElement('span');
        bannerText.id = 'gpuModalBannerText';
        banner.appendChild(document.createElement('span')).textContent = '⚠';
        banner.appendChild(bannerText);
        content.appendChild(banner);

        var warnBox = document.createElement('div');
        warnBox.className = 'gpu-modal-warnbox';
        warnBox.id = 'gpuModalWarnBox';
        warnBox.style.display = 'none';
        var warnList = document.createElement('div');
        warnList.id = 'gpuModalWarnList';
        warnBox.appendChild(warnList);
        content.appendChild(warnBox);

        var disabled = document.createElement('div');
        disabled.className = 'gpu-modal-empty';
        disabled.id = 'gpuModalDisabled';
        disabled.style.display = 'none';
        disabled.textContent = 'GPU-delen är avstängd. Sätt gpu.stats-url under Inställningar → GPU.';
        content.appendChild(disabled);

        var kpi = document.createElement('section');
        kpi.className = 'gpu-modal-kpi';
        kpi.innerHTML = ''
            + '<article class="gpu-modal-kpi-card"><div class="gpu-modal-kpi-label">Belastning</div><div class="gpu-modal-kpi-value" id="gmUtil">-</div></article>'
            + '<article class="gpu-modal-kpi-card"><div class="gpu-modal-kpi-label">VRAM</div><div class="gpu-modal-kpi-value" id="gmVram">-</div><div class="gpu-modal-kpi-sub" id="gmVramSub"></div></article>'
            + '<article class="gpu-modal-kpi-card"><div class="gpu-modal-kpi-label">Temperatur</div><div class="gpu-modal-kpi-value" id="gmTemp">-</div></article>'
            + '<article class="gpu-modal-kpi-card"><div class="gpu-modal-kpi-label">Effekt</div><div class="gpu-modal-kpi-value" id="gmPow">-</div><div class="gpu-modal-kpi-sub" id="gmPowSub"></div></article>';
        content.appendChild(kpi);

        var charts = document.createElement('section');
        charts.className = 'gpu-modal-charts';
        charts.id = 'gpuModalCharts';
        charts.innerHTML = ''
            + '<article class="gpu-modal-card"><header class="gpu-modal-card-head"><div class="gpu-modal-card-title">Belastning</div></header><div class="gpu-modal-chart"><svg id="gmChartUtil" class="gpu-modal-svg" viewBox="0 0 520 200" preserveAspectRatio="none"></svg></div></article>'
            + '<article class="gpu-modal-card"><header class="gpu-modal-card-head"><div class="gpu-modal-card-title">VRAM</div></header><div class="gpu-modal-chart"><svg id="gmChartMem" class="gpu-modal-svg" viewBox="0 0 520 200" preserveAspectRatio="none"></svg></div></article>';
        content.appendChild(charts);

        var modelsSec = document.createElement('section');
        modelsSec.className = 'gpu-modal-section';
        modelsSec.innerHTML = ''
            + '<header class="gpu-modal-section-head"><h3>Laddade modeller</h3></header>'
            + '<div class="gpu-modal-table-wrap"><table class="gpu-modal-table"><thead><tr><th>Modell</th><th>Storlek</th><th>Placering</th><th>Kontext</th><th>Går ut</th><th>Åtgärd</th></tr></thead><tbody id="gmModels"></tbody></table></div>';
        content.appendChild(modelsSec);

        var procSec = document.createElement('section');
        procSec.className = 'gpu-modal-section';
        procSec.innerHTML = ''
            + '<header class="gpu-modal-section-head"><h3>GPU-processer</h3></header>'
            + '<div class="gpu-modal-table-wrap"><table class="gpu-modal-table"><thead><tr><th>PID</th><th>Process</th><th>VRAM</th></tr></thead><tbody id="gmProcs"></tbody></table></div>';
        content.appendChild(procSec);

        var actionsSec = document.createElement('section');
        actionsSec.className = 'gpu-modal-section';
        actionsSec.innerHTML = ''
            + '<header class="gpu-modal-section-head"><h3>Åtgärder</h3></header>'
            + '<div class="gpu-modal-actions"><button type="button" id="gmUnloadAll">Ladda ur alla modeller</button></div>'
            + '<div class="gpu-modal-help">Nästa fråga laddar om modellerna, första svaret blir långsammare.</div>';
        content.appendChild(actionsSec);
    }

    function loadHistory(minutes) {
        var key = minutes;
        if (historyCache[key]) {
            drawHistory(historyCache[key]);
            return;
        }
        fetch('/api/system/gpu/history?minutes=' + minutes, { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (json) {
                if (json) {
                    historyCache[key] = json;
                    drawHistory(json);
                }
            })
            .catch(function () { });
    }

    function mergeLive(data) {
        // live points will be added by poller history if any; just redraw
        var minutes = range;
        if (historyCache[minutes]) {
            drawHistory(historyCache[minutes]);
        }
    }

    function drawHistory(hist) {
        if (!hist || !hist.points) return;
        var gpu = lastData && lastData.gpu ? lastData.gpu : {};
        var mt = typeof gpu.memTotalMiB === 'number' ? gpu.memTotalMiB : null;
        var points = hist.points;
        var utilVals = points.map(function (p) { return (typeof p.util === 'number') ? p.util : null; }).filter(function (v) { return v !== null; });
        var memVals = points.map(function (p) {
            if (typeof p.util === 'number' && typeof p.mem === 'number' && mt && mt > 0) return (p.mem / mt) * 100;
            if (typeof p.mem === 'number' && mt && mt > 0) return (p.mem / mt) * 100;
            return null;
        }).filter(function (v) { return v !== null; });
        if (memVals.length === 0) {
            memVals = points.map(function (p) {
                if (typeof p.mem === 'number' && mt && mt > 0) return (p.mem / mt) * 100;
                return null;
            }).filter(function (v) { return v !== null; });
        }
        drawChart(document.getElementById('gmChartUtil'), utilVals, 100);
        drawChart(document.getElementById('gmChartMem'), memVals, 100);
    }

    function drawChart(svg, values, maxVal) {
        if (!svg) return;
        svg.innerHTML = '';
        var w = 520, h = 180, pad = 24;
        var plotW = w - pad * 2, plotH = h - pad * 2;
        for (var i = 0; i <= 4; i++) {
            var y = pad + (plotH / 4) * i;
            var line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            line.setAttribute('x1', pad); line.setAttribute('y1', y); line.setAttribute('x2', w - pad); line.setAttribute('y2', y);
            line.setAttribute('class', 'grid');
            svg.appendChild(line);
        }
        if (!values || values.length === 0) return;
        var pts = [];
        for (var j = 0; j < values.length; j++) {
            var x = pad + (plotW * j / Math.max(1, values.length - 1));
            var v = values[j];
            var mv = maxVal || 100;
            var yv = pad + plotH - (plotH * Math.max(0, Math.min(mv, v)) / mv);
            pts.push(x + ',' + yv);
        }
        var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', 'M' + pts.join(' L'));
        path.setAttribute('class', 'line');
        svg.appendChild(path);
    }

    function renderLive(data) {
        if (!data) return;
        var gpu = data.gpu || {};
        var models = data.models || [];
        var warns = data.warnings || [];
        var subEl = document.getElementById('gpuModalSub');
        var liveEl = document.getElementById('gpuModalLive');
        var liveTextEl = document.getElementById('gpuModalLiveText');
        var banner = document.getElementById('gpuModalBanner');
        var bannerText = document.getElementById('gpuModalBannerText');
        var warnBox = document.getElementById('gpuModalWarnBox');
        var warnList = document.getElementById('gpuModalWarnList');
        var disabled = document.getElementById('gpuModalDisabled');
        var charts = document.getElementById('gpuModalCharts');

        if (gpu.reason === 'disabled') {
            if (disabled) disabled.style.display = 'block';
            if (charts) charts.style.display = 'none';
            if (banner) banner.style.display = 'none';
            if (warnBox) warnBox.style.display = 'none';
            if (subEl) subEl.textContent = 'GPU avstängd';
            return;
        }
        if (disabled) disabled.style.display = 'none';
        if (charts) charts.style.display = 'grid';
        if (subEl) subEl.textContent = (gpu.name || 'Okänd GPU') + (gpu.memTotalMiB ? ' · ' + (gpu.memTotalMiB/1024).toFixed(0) + ' GB' : '');
        var stale = !!data._stale || gpu.available === false;
        if (liveEl) liveEl.classList.toggle('stale', stale);
        if (liveTextEl) liveTextEl.textContent = stale ? 'Ingen kontakt' : 'Live';
        if (gpu.available === false) {
            if (banner) { banner.style.display = 'flex'; bannerText.textContent = 'Ingen kontakt med gpu-stats'; }
        } else {
            if (banner) banner.style.display = 'none';
        }
        if (warnBox) {
            if (warns.length > 0) {
                warnBox.style.display = 'flex';
                warnList.innerHTML = '';
                warns.forEach(function (w) {
                    var div = document.createElement('div');
                    div.className = 'gpu-modal-warnitem';
                    div.textContent = w.message || w.code || '';
                    warnList.appendChild(div);
                });
            } else {
                warnBox.style.display = 'none';
            }
        }

        document.getElementById('gmUtil').textContent = typeof gpu.utilPct === 'number' ? gpu.utilPct + '%' : '-';
        var mu = typeof gpu.memUsedMiB === 'number' ? gpu.memUsedMiB : null;
        var mt = typeof gpu.memTotalMiB === 'number' ? gpu.memTotalMiB : null;
        document.getElementById('gmVram').textContent = (mu != null && mt != null) ? (mu/1024).toFixed(1) + ' / ' + (mt/1024).toFixed(1) + ' GB' : '-';
        document.getElementById('gmVramSub').textContent = mt != null ? 'Totalt ' + (mt/1024).toFixed(1) + ' GB' : '';
        document.getElementById('gmTemp').textContent = typeof gpu.tempC === 'number' ? gpu.tempC + ' °C' : '-';
        document.getElementById('gmPow').textContent = typeof gpu.powerW === 'number' ? gpu.powerW.toFixed(1) + ' W' : '-';
        document.getElementById('gmPowSub').textContent = typeof gpu.powerLimitW === 'number' ? 'Gräns ' + gpu.powerLimitW.toFixed(0) + ' W' : '';

        var mb = document.getElementById('gmModels');
        if (mb) {
            mb.innerHTML = '';
            if (!models || models.length === 0) {
                var tr = document.createElement('tr');
                var td = document.createElement('td');
                td.colSpan = 6;
                td.textContent = 'Inga laddade modeller';
                tr.appendChild(td);
                mb.appendChild(tr);
            } else {
                models.forEach(function (m) {
                    var tr = document.createElement('tr');
                    var td1 = document.createElement('td'); td1.textContent = m.name || ''; tr.appendChild(td1);
                    var td2 = document.createElement('td'); var sb = typeof m.sizeBytes === 'number' ? m.sizeBytes : null; td2.textContent = sb != null ? (sb/1024/1024/1024).toFixed(1) + ' GB' : '-'; tr.appendChild(td2);
                    var td3 = document.createElement('td'); var gp = typeof m.gpuPct === 'number' ? m.gpuPct : null; td3.textContent = gp == null ? '-' : (gp >= 100 ? '100 % GPU' : gp + ' % GPU / ' + (100-gp) + ' % CPU'); tr.appendChild(td3);
                    var td4 = document.createElement('td'); td4.textContent = typeof m.contextLength === 'number' ? m.contextLength : '-'; tr.appendChild(td4);
                    var td5 = document.createElement('td'); td5.textContent = m.expiresAt ? String(m.expiresAt).replace('T', ' ').replace('Z','') : '-'; tr.appendChild(td5);
                    var td6 = document.createElement('td');
                    var btn = document.createElement('button');
                    btn.type = 'button';
                    btn.textContent = 'Ladda ur';
                    btn.addEventListener('click', function () {
                        unloadModel(m.name);
                    });
                    td6.appendChild(btn);
                    tr.appendChild(td6);
                    mb.appendChild(tr);
                });
            }
        }

        var pb = document.getElementById('gmProcs');
        if (pb) {
            pb.innerHTML = '';
            var procs = gpu.processes || [];
            if (procs.length === 0) {
                var tr = document.createElement('tr');
                var td = document.createElement('td'); td.colSpan = 3; td.textContent = 'Inga processer'; tr.appendChild(td); pb.appendChild(tr);
            } else {
                procs.forEach(function (p) {
                    var tr = document.createElement('tr');
                    var t1 = document.createElement('td'); t1.textContent = p.pid != null ? p.pid : '-'; tr.appendChild(t1);
                    var t2 = document.createElement('td'); t2.textContent = p.name || '-'; tr.appendChild(t2);
                    var t3 = document.createElement('td'); var mv = typeof p.memMiB === 'number' ? p.memMiB : null; t3.textContent = mv != null ? mv + ' MiB' : '-'; tr.appendChild(t3);
                    pb.appendChild(tr);
                });
            }
        }

        var unloadAll = document.getElementById('gmUnloadAll');
        if (unloadAll) {
            unloadAll.onclick = function () {
                if (!confirm('Ladda ur alla modeller?')) return;
                unloadModel(null);
            };
        }
    }

    function unloadModel(name) {
        fetch('/api/system/ollama/unload', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv' },
            body: JSON.stringify(name ? { model: name } : {})
        }).then(function () {
            alert('Urladdningsbegäran skickad. Kontrollera /api/ps om modellen försvunnit.');
        }).catch(function () {
            alert('Kunde inte skicka begäran.');
        });
    }

    window.GpuModal = {
        open: openModal,
        close: closeModal
    };

    // wire sidebar card
    function wireSidebar() {
        var card = document.getElementById('gpuSidebarCard');
        if (!card) return;
        card.style.cursor = 'pointer';
        card.addEventListener('click', function (e) {
            if (e.target.tagName === 'A') return;
            e.preventDefault();
            openModal();
        });
        var link = card.querySelector('.gpu-sidebar-link');
        if (link) {
            link.addEventListener('click', function (e) {
                e.preventDefault();
                openModal();
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', wireSidebar);
    } else {
        wireSidebar();
    }

    // handle ?gpu=1
    try {
        var params = new URLSearchParams(window.location.search);
        if (params.get('gpu') === '1') {
            setTimeout(function () {
                openModal();
                try { history.replaceState({}, document.title, window.location.pathname); } catch (e) { }
            }, 50);
        }
    } catch (e) { }
})();
