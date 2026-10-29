/* GPU-modal: nyckeltal, grafer med historik, laddade modeller och åtgärder.
   Klassiskt skript utan moduler (luftgappad miljö, inga bibliotek). Öppnas via GpuModal.open(),
   GPU-kortet i sidopanelen (sidebar.js) eller tangenten "g". Kräver gpu-poller.js. */
(function () {
    'use strict';

    var SVG_NS = 'http://www.w3.org/2000/svg';
    var RANGES = [
        { min: 5, label: '5 min' },
        { min: 30, label: '30 min' },
        { min: 120, label: '2 h' },
        { min: 1440, label: '24 h' }
    ];
    var CHART_H = 128;

    var modal = null;
    var backdrop = null;
    var content = null;
    var lastFocus = null;
    var pollerSub = null;
    var range = 30;
    var lastData = null;
    var isOpen = false;
    var series = { util: [], mem: [] };
    var histTimer = null;
    var histToken = 0;
    var resizeObs = null;
    var msgTimer = null;
    var confirmTimer = null;

    /* ---------- hjälpare ---------- */

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    }

    function svgEl(tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var key in attrs) {
            if (Object.prototype.hasOwnProperty.call(attrs, key)) node.setAttribute(key, attrs[key]);
        }
        return node;
    }

    function byId(id) { return document.getElementById(id); }
    function isNum(v) { return typeof v === 'number' && isFinite(v); }
    function pct(v) { return Math.round(v) + ' %'; }
    function gb(mib) { return (mib / 1024).toFixed(1).replace('.', ',') + ' GB'; }

    function clock(ms, withSeconds) {
        var opts = { hour: '2-digit', minute: '2-digit' };
        if (withSeconds) opts.second = '2-digit';
        try { return new Date(ms).toLocaleTimeString('sv-SE', opts); } catch (e) { return ''; }
    }

    function isTyping(node) {
        if (!node) return false;
        var tag = node.tagName;
        return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || node.isContentEditable === true;
    }

    /* ---------- uppbyggnad ---------- */

    function loadCss() {
        if (byId('gpuModalCss')) return;
        var link = document.createElement('link');
        link.id = 'gpuModalCss';
        link.rel = 'stylesheet';
        link.href = '/css/gpu-modal.css';
        document.head.appendChild(link);
    }

    function createModal() {
        if (modal) return;
        loadCss();
        backdrop = el('div', 'gpu-modal-backdrop');
        backdrop.setAttribute('aria-hidden', 'true');

        modal = el('div', 'gpu-modal');
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-labelledby', 'gpuModalTitle');
        modal.tabIndex = -1;

        var header = el('header', 'gpu-modal-header');
        var titleWrap = el('div', 'gpu-modal-titlewrap');
        var title = el('h2', null, 'GPU och modeller');
        title.id = 'gpuModalTitle';
        var sub = el('div', 'gpu-modal-sub');
        sub.id = 'gpuModalSub';
        titleWrap.appendChild(title);
        titleWrap.appendChild(sub);

        var actions = el('div', 'gpu-modal-head-actions');
        var live = el('div', 'gpu-modal-live');
        live.id = 'gpuModalLive';
        live.appendChild(el('span', 'gpu-modal-live-dot'));
        var liveText = el('span', null, 'Live');
        liveText.id = 'gpuModalLiveText';
        live.appendChild(liveText);

        var rangeWrap = el('div', 'gpu-modal-range');
        rangeWrap.setAttribute('role', 'group');
        rangeWrap.setAttribute('aria-label', 'Tidsintervall');
        try {
            var saved = parseInt(localStorage.getItem('gpuModalRange'), 10);
            if (RANGES.some(function (r) { return r.min === saved; })) range = saved;
        } catch (e) { /* lagring kan saknas */ }
        RANGES.forEach(function (r) {
            var b = el('button', r.min === range ? 'active' : '', r.label);
            b.type = 'button';
            b.setAttribute('aria-pressed', r.min === range ? 'true' : 'false');
            b.addEventListener('click', function () {
                range = r.min;
                try { localStorage.setItem('gpuModalRange', String(range)); } catch (e) { /* ignoreras */ }
                Array.prototype.forEach.call(rangeWrap.children, function (x) {
                    var on = x === b;
                    x.classList.toggle('active', on);
                    x.setAttribute('aria-pressed', on ? 'true' : 'false');
                });
                loadHistory();
            });
            rangeWrap.appendChild(b);
        });

        var close = el('button', 'gpu-modal-close', '×');
        close.type = 'button';
        close.setAttribute('aria-label', 'Stäng');
        close.addEventListener('click', closeModal);

        actions.appendChild(live);
        actions.appendChild(rangeWrap);
        actions.appendChild(close);
        header.appendChild(titleWrap);
        header.appendChild(actions);

        content = el('div', 'gpu-modal-content');
        modal.appendChild(header);
        modal.appendChild(content);
        document.body.appendChild(backdrop);
        document.body.appendChild(modal);

        backdrop.addEventListener('click', closeModal);
    }

    function kpiCard(label, valueId, subId) {
        var card = el('article', 'gpu-modal-kpi-card');
        card.appendChild(el('div', 'gpu-modal-kpi-label', label));
        var value = el('div', 'gpu-modal-kpi-value', '-');
        value.id = valueId;
        card.appendChild(value);
        var sub = el('div', 'gpu-modal-kpi-sub');
        sub.id = subId;
        card.appendChild(sub);
        return card;
    }

    function chartCard(title, key) {
        var card = el('article', 'gpu-modal-card');
        var head = el('header', 'gpu-modal-card-head');
        head.appendChild(el('div', 'gpu-modal-card-title', title));
        var stats = el('div', 'gpu-modal-card-stats');
        stats.id = 'gmStats_' + key;
        head.appendChild(stats);
        card.appendChild(head);
        var host = el('div', 'gpu-chart');
        host.id = 'gmChart_' + key;
        card.appendChild(host);
        return card;
    }

    function tableSection(title, headCols, bodyId, extraHead) {
        var section = el('section', 'gpu-modal-section');
        var head = el('header', 'gpu-modal-section-head');
        head.appendChild(el('h3', null, title));
        if (extraHead) head.appendChild(extraHead);
        section.appendChild(head);
        var wrap = el('div', 'gpu-modal-table-wrap');
        var table = el('table', 'gpu-modal-table');
        var thead = el('thead');
        var tr = el('tr');
        headCols.forEach(function (c) { tr.appendChild(el('th', null, c)); });
        thead.appendChild(tr);
        var tbody = el('tbody');
        tbody.id = bodyId;
        table.appendChild(thead);
        table.appendChild(tbody);
        wrap.appendChild(table);
        section.appendChild(wrap);
        return section;
    }

    function buildContent() {
        content.textContent = '';

        var banner = el('div', 'gpu-modal-banner');
        banner.id = 'gpuModalBanner';
        banner.hidden = true;
        banner.appendChild(el('span', null, '⚠'));
        var bannerText = el('span');
        bannerText.id = 'gpuModalBannerText';
        banner.appendChild(bannerText);
        content.appendChild(banner);

        var warnBox = el('div', 'gpu-modal-warnbox');
        warnBox.id = 'gpuModalWarnBox';
        warnBox.hidden = true;
        warnBox.setAttribute('aria-live', 'polite');
        content.appendChild(warnBox);

        var disabled = el('div', 'gpu-modal-empty',
            'GPU-delen är avstängd. Ange gpu.stats-url under Inställningar → GPU.');
        disabled.id = 'gpuModalDisabled';
        disabled.hidden = true;
        content.appendChild(disabled);

        var kpi = el('section', 'gpu-modal-kpi');
        kpi.id = 'gpuModalKpi';
        kpi.appendChild(kpiCard('Belastning', 'gmUtil', 'gmUtilSub'));
        kpi.appendChild(kpiCard('VRAM', 'gmVram', 'gmVramSub'));
        kpi.appendChild(kpiCard('Temperatur', 'gmTemp', 'gmTempSub'));
        kpi.appendChild(kpiCard('Effekt', 'gmPow', 'gmPowSub'));
        content.appendChild(kpi);

        var charts = el('section', 'gpu-modal-charts');
        charts.id = 'gpuModalCharts';
        charts.appendChild(chartCard('Belastning', 'util'));
        charts.appendChild(chartCard('VRAM-användning', 'mem'));
        content.appendChild(charts);

        var unloadAll = el('button', 'gpu-modal-btn', 'Ladda ur alla');
        unloadAll.type = 'button';
        unloadAll.id = 'gmUnloadAll';
        unloadAll.addEventListener('click', onUnloadAll);

        var lower = el('div', 'gpu-modal-lower');
        lower.id = 'gpuModalLower';
        var models = tableSection('Laddade modeller',
            ['Modell', 'Storlek', 'Placering', 'Kontext', 'Går ut om', ''], 'gmModels', unloadAll);
        models.classList.add('gpu-modal-models');
        var procs = tableSection('GPU-processer', ['PID', 'Process', 'VRAM'], 'gmProcs');
        procs.classList.add('gpu-modal-procs');
        lower.appendChild(models);
        lower.appendChild(procs);
        content.appendChild(lower);

        var foot = el('div', 'gpu-modal-foot');
        var help = el('span', 'gpu-modal-help',
            'Urladdade modeller laddas om vid nästa fråga, så första svaret blir långsammare.');
        var msg = el('span', 'gpu-modal-msg');
        msg.id = 'gmMsg';
        msg.setAttribute('role', 'status');
        msg.setAttribute('aria-live', 'polite');
        foot.appendChild(help);
        foot.appendChild(msg);
        content.appendChild(foot);

        if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
        if (typeof ResizeObserver !== 'undefined') {
            resizeObs = new ResizeObserver(function () { drawAll(); });
            resizeObs.observe(charts);
        }
    }

    /* ---------- öppna/stäng ---------- */

    function onKey(e) {
        if (!isOpen) {
            if (e.key === 'g' && !e.ctrlKey && !e.metaKey && !e.altKey && !isTyping(e.target)) {
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
        if (e.key === 'Tab') trapFocus(e);
    }

    function trapFocus(e) {
        var focusable = modal.querySelectorAll('button:not([disabled]), [href], input, select, textarea');
        if (focusable.length === 0) return;
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (e.shiftKey && (document.activeElement === first || document.activeElement === modal)) {
            last.focus();
            e.preventDefault();
        } else if (!e.shiftKey && document.activeElement === last) {
            first.focus();
            e.preventDefault();
        }
    }

    function openModal() {
        if (isOpen) return;
        createModal();
        lastFocus = document.activeElement;
        isOpen = true;
        series = { util: [], mem: [] };
        buildContent();
        modal.classList.add('open');
        backdrop.classList.add('open');
        document.body.style.overflow = 'hidden';
        setTimeout(function () { if (isOpen) modal.focus(); }, 10);

        if (window.GpuPoller) {
            pollerSub = function (data) {
                lastData = data;
                renderLive(data);
                addLivePoint(data);
            };
            window.GpuPoller.subscribe(pollerSub);
            var cached = window.GpuPoller.getData();
            if (cached) pollerSub(cached);
        }
        loadHistory();
    }

    function closeModal() {
        if (!isOpen) return;
        isOpen = false;
        modal.classList.remove('open');
        backdrop.classList.remove('open');
        document.body.style.overflow = '';
        if (histTimer) { clearTimeout(histTimer); histTimer = null; }
        if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
        if (window.GpuPoller && pollerSub) {
            try { window.GpuPoller.unsubscribe(pollerSub); } catch (e) { /* ignoreras */ }
        }
        pollerSub = null;
        if (lastFocus && typeof lastFocus.focus === 'function') {
            try { lastFocus.focus(); } catch (e) { /* ignoreras */ }
        }
    }

    /* ---------- historik ---------- */

    function memTotal() {
        var gpu = lastData && lastData.gpu;
        return gpu && isNum(gpu.memTotalMiB) && gpu.memTotalMiB > 0 ? gpu.memTotalMiB : null;
    }

    function loadHistory() {
        var token = ++histToken;
        if (histTimer) { clearTimeout(histTimer); histTimer = null; }
        fetch('/api/system/gpu/history?minutes=' + range, { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (json) {
                if (token !== histToken || !isOpen) return;
                if (json && Array.isArray(json.points)) {
                    var util = [];
                    var mem = [];
                    json.points.forEach(function (p) {
                        var t = Date.parse(p.t);
                        if (!isFinite(t)) return;
                        if (p.gap === true) {
                            util.push({ t: t, v: null });
                            mem.push({ t: t, v: null });
                            return;
                        }
                        util.push({ t: t, v: isNum(p.util) ? p.util : null });
                        mem.push({ t: t, v: null, raw: isNum(p.mem) ? p.mem : null });
                    });
                    series = { util: util, mem: mem };
                    fillMemPercent();
                    drawAll();
                }
                // Minutupplösning (över 2 h) har inga livepunkter: hämta om med jämna mellanrum.
                if (range > 120) histTimer = setTimeout(loadHistory, 30000);
            })
            .catch(function () { /* grafen behåller senaste data */ });
    }

    function fillMemPercent() {
        var total = memTotal();
        series.mem.forEach(function (p) {
            if (p.raw != null && total) p.v = p.raw / total * 100;
        });
    }

    function addLivePoint(data) {
        var gpu = data && data.gpu;
        if (!gpu || gpu.available === false || gpu.reason) return;
        if (range <= 120) {
            var now = Date.now();
            var total = isNum(gpu.memTotalMiB) && gpu.memTotalMiB > 0 ? gpu.memTotalMiB : null;
            series.util.push({ t: now, v: isNum(gpu.utilPct) ? gpu.utilPct : null });
            series.mem.push({
                t: now,
                raw: isNum(gpu.memUsedMiB) ? gpu.memUsedMiB : null,
                v: isNum(gpu.memUsedMiB) && total ? gpu.memUsedMiB / total * 100 : null
            });
            var cutoff = now - range * 60000;
            while (series.util.length > 1 && series.util[0].t < cutoff) series.util.shift();
            while (series.mem.length > 1 && series.mem[0].t < cutoff) series.mem.shift();
        } else {
            fillMemPercent();
        }
        drawAll();
    }

    /* ---------- grafer ---------- */

    function drawAll() {
        if (!isOpen) return;
        drawChart('util', series.util, {
            color: '#38bdf8',
            text: function (p) { return pct(p.v); }
        });
        drawChart('mem', series.mem, {
            color: '#818cf8',
            text: function (p) {
                var total = memTotal();
                return pct(p.v) + (total ? ' (' + gb(p.v / 100 * total) + ')' : '');
            }
        });
    }

    function valid(points) {
        return points.filter(function (p) { return isNum(p.v); });
    }

    function renderStats(key, points, opts) {
        var node = byId('gmStats_' + key);
        if (!node) return;
        node.textContent = '';
        var vals = valid(points);
        if (vals.length === 0) { node.textContent = 'Ingen data'; return; }
        var min = vals[0];
        var max = vals[0];
        var sum = 0;
        vals.forEach(function (p) {
            if (p.v < min.v) min = p;
            if (p.v > max.v) max = p;
            sum += p.v;
        });
        function part(label, p, value) {
            var span = el('span', 'gpu-stat');
            span.appendChild(el('span', 'gpu-stat-label', label));
            span.appendChild(el('strong', null, value != null ? value : opts.text(p)));
            return span;
        }
        node.appendChild(part('Min', min));
        node.appendChild(part('Snitt', null, pct(sum / vals.length)));
        node.appendChild(part('Max', max));
    }

    function drawChart(key, points, opts) {
        var host = byId('gmChart_' + key);
        if (!host) return;
        renderStats(key, points, opts);
        host.textContent = '';
        var width = Math.max(240, host.clientWidth || 480);
        var padL = 40, padR = 12, padT = 12, padB = 20;
        var plotW = width - padL - padR;
        var plotH = CHART_H - padT - padB;

        var svg = svgEl('svg', {
            'class': 'gpu-svg', width: width, height: CHART_H,
            viewBox: '0 0 ' + width + ' ' + CHART_H, role: 'img'
        });
        var vals = valid(points);
        var last = vals.length ? vals[vals.length - 1] : null;
        svg.setAttribute('aria-label', (key === 'util' ? 'Belastning' : 'VRAM-användning')
            + (last ? ', senast ' + opts.text(last) : ', ingen data'));

        function yOf(v) { return padT + plotH * (1 - Math.max(0, Math.min(100, v)) / 100); }

        [0, 25, 50, 75, 100].forEach(function (g) {
            var y = yOf(g);
            svg.appendChild(svgEl('line', { x1: padL, x2: width - padR, y1: y, y2: y, 'class': g === 0 ? 'axis' : 'grid' }));
        });
        [0, 50, 100].forEach(function (g) {
            var t = svgEl('text', { x: padL - 6, y: yOf(g) + 4, 'class': 'ylabel', 'text-anchor': 'end' });
            t.textContent = g + ' %';
            svg.appendChild(t);
        });

        if (points.length === 0) { host.appendChild(svg); return; }

        var tEnd = Math.max(Date.now(), points[points.length - 1].t);
        var tStart = Math.max(tEnd - range * 60000, points[0].t);
        if (tEnd - tStart < 1000) tStart = tEnd - 60000;
        function xOf(t) { return padL + plotW * (t - tStart) / (tEnd - tStart); }

        [tStart, (tStart + tEnd) / 2, tEnd].forEach(function (t, i) {
            var tx = svgEl('text', {
                x: i === 0 ? padL : i === 1 ? padL + plotW / 2 : width - padR, y: CHART_H - 5,
                'class': 'xlabel', 'text-anchor': i === 0 ? 'start' : i === 1 ? 'middle' : 'end'
            });
            tx.textContent = clock(t);
            svg.appendChild(tx);
        });

        // Linje och yta, uppdelade vid luckor.
        var segs = [];
        var cur = [];
        points.forEach(function (p) {
            if (isNum(p.v)) { cur.push(p); } else { if (cur.length) segs.push(cur); cur = []; svg.appendChild(svgEl('line', { x1: xOf(p.t), x2: xOf(p.t), y1: padT, y2: padT + plotH, 'class': 'gap' })); }
        });
        if (cur.length) segs.push(cur);
        var bottom = padT + plotH;
        segs.forEach(function (seg) {
            var d = seg.map(function (p, i) { return (i ? 'L' : 'M') + xOf(p.t).toFixed(1) + ' ' + yOf(p.v).toFixed(1); }).join(' ');
            if (seg.length > 1) {
                svg.appendChild(svgEl('path', {
                    d: d + ' L' + xOf(seg[seg.length - 1].t).toFixed(1) + ' ' + bottom + ' L' + xOf(seg[0].t).toFixed(1) + ' ' + bottom + ' Z',
                    fill: opts.color, 'fill-opacity': '0.14', stroke: 'none'
                }));
            }
            svg.appendChild(svgEl('path', { d: d, fill: 'none', stroke: opts.color, 'stroke-width': '2', 'stroke-linejoin': 'round' }));
        });

        // Min- och maxmarkörer med procentvärde.
        if (vals.length > 1) {
            var min = vals[0];
            var max = vals[0];
            vals.forEach(function (p) { if (p.v < min.v) min = p; if (p.v > max.v) max = p; });
            var marks = [{ p: max, above: true }];
            if (max.v - min.v >= 1) marks.push({ p: min, above: false });
            marks.forEach(function (m) {
                var x = xOf(m.p.t);
                var y = yOf(m.p.v);
                svg.appendChild(svgEl('circle', { cx: x, cy: y, r: 3, fill: '#0b1120', stroke: opts.color, 'stroke-width': '1.5' }));
                var ly = m.above ? y - 7 : y + 15;
                if (ly < padT + 6) ly = y + 15;
                if (ly > bottom - 2) ly = y - 7;
                var anchor = x < padL + 22 ? 'start' : x > width - padR - 22 ? 'end' : 'middle';
                var label = svgEl('text', { x: x, y: ly, 'class': 'mark', 'text-anchor': anchor, fill: opts.color });
                label.textContent = pct(m.p.v);
                svg.appendChild(label);
            });
        }

        if (last) svg.appendChild(svgEl('circle', { cx: xOf(last.t), cy: yOf(last.v), r: 3.5, fill: opts.color }));

        var guide = svgEl('line', { y1: padT, y2: padT + plotH, 'class': 'guide', visibility: 'hidden' });
        svg.appendChild(guide);
        host.appendChild(svg);
        var tip = el('div', 'gpu-tip');
        tip.hidden = true;
        host.appendChild(tip);

        host.onpointermove = function (e) {
            var rect = svg.getBoundingClientRect();
            var x = e.clientX - rect.left;
            if (x < padL || x > width - padR || vals.length === 0) { hideTip(); return; }
            var t = tStart + (x - padL) / plotW * (tEnd - tStart);
            var best = vals[0];
            vals.forEach(function (p) { if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p; });
            var bx = xOf(best.t);
            guide.setAttribute('x1', bx);
            guide.setAttribute('x2', bx);
            guide.setAttribute('visibility', 'visible');
            tip.textContent = clock(best.t, true) + '  ' + opts.text(best);
            tip.hidden = false;
            var left = bx + 10;
            if (left + tip.offsetWidth > width) left = bx - tip.offsetWidth - 10;
            tip.style.left = Math.max(0, left) + 'px';
        };
        host.onpointerleave = hideTip;
        function hideTip() {
            tip.hidden = true;
            guide.setAttribute('visibility', 'hidden');
        }
    }

    /* ---------- liveinnehåll ---------- */

    function eta(iso) {
        var t = Date.parse(iso);
        if (!isFinite(t)) return '-';
        var s = Math.round((t - Date.now()) / 1000);
        if (s <= 0) return 'strax';
        if (s < 90) return s + ' s';
        if (s < 3600) return Math.round(s / 60) + ' min';
        return clock(t);
    }

    function setText(id, text) {
        var node = byId(id);
        if (node) node.textContent = text;
    }

    function renderLive(data) {
        if (!data || !isOpen) return;
        var gpu = data.gpu || {};
        var models = data.models || [];
        var warns = data.warnings || [];
        var disabledBox = byId('gpuModalDisabled');
        var show = gpu.reason !== 'disabled';

        ['gpuModalKpi', 'gpuModalCharts'].forEach(function (id) {
            var node = byId(id);
            if (node) node.hidden = !show;
        });
        if (disabledBox) disabledBox.hidden = show;
        if (!show) {
            setText('gpuModalSub', 'GPU avstängd');
        } else {
            setText('gpuModalSub', (gpu.name || 'Okänd GPU') + (isNum(gpu.memTotalMiB) ? ' · ' + gb(gpu.memTotalMiB) : ''));
        }

        var stale = !!data._stale || gpu.available === false;
        var live = byId('gpuModalLive');
        if (live) live.classList.toggle('stale', stale);
        setText('gpuModalLiveText', stale ? 'Ingen kontakt' : 'Live');
        var banner = byId('gpuModalBanner');
        if (banner) {
            banner.hidden = !(show && gpu.available === false);
            setText('gpuModalBannerText', 'Ingen kontakt med gpu-stats. Visar senast kända värden.');
        }

        var warnBox = byId('gpuModalWarnBox');
        if (warnBox) {
            warnBox.textContent = '';
            warns.forEach(function (w) { warnBox.appendChild(el('div', 'gpu-modal-warnitem', w.message || w.code || '')); });
            warnBox.hidden = warns.length === 0;
        }

        var mu = isNum(gpu.memUsedMiB) ? gpu.memUsedMiB : null;
        var mt = isNum(gpu.memTotalMiB) ? gpu.memTotalMiB : null;
        setText('gmUtil', isNum(gpu.utilPct) ? pct(gpu.utilPct) : '-');
        setText('gmUtilSub', gpu.available === false ? 'ingen kontakt' : (isNum(gpu.utilPct) && gpu.utilPct > 40 ? 'arbetar' : 'inaktiv'));
        setText('gmVram', mu != null && mt != null ? gb(mu) + ' / ' + gb(mt) : '-');
        setText('gmVramSub', mu != null && mt != null ? pct(mu / mt * 100) + ' använt · ' + gb(mt - mu) + ' ledigt' : '');
        setText('gmTemp', isNum(gpu.tempC) ? gpu.tempC + ' °C' : '-');
        setText('gmTempSub', '');
        setText('gmPow', isNum(gpu.powerW) ? Math.round(gpu.powerW) + ' W' : '-');
        setText('gmPowSub', isNum(gpu.powerLimitW) ? 'gräns ' + Math.round(gpu.powerLimitW) + ' W' : '');

        renderModels(models);
        renderProcs(gpu.processes || []);
    }

    function cell(tr, text, cls) {
        var td = el('td', cls || null, text);
        tr.appendChild(td);
        return td;
    }

    function renderModels(models) {
        var body = byId('gmModels');
        if (!body) return;
        body.textContent = '';
        var all = byId('gmUnloadAll');
        if (all) all.disabled = models.length === 0;
        if (models.length === 0) {
            var empty = el('tr');
            var td = cell(empty, 'Inga laddade modeller', 'gpu-empty');
            td.colSpan = 6;
            body.appendChild(empty);
            return;
        }
        models.forEach(function (m) {
            var tr = el('tr');
            cell(tr, m.name || '', 'gpu-name');
            cell(tr, isNum(m.sizeBytes) ? gb(m.sizeBytes / 1048576) : '-');
            var place = cell(tr, '');
            var gp = isNum(m.gpuPct) ? m.gpuPct : null;
            if (gp == null) {
                place.textContent = '-';
            } else {
                var full = gp >= 100;
                var bar = el('span', 'gpu-bar' + (full ? '' : ' partial'));
                var fill = el('span');
                fill.style.width = Math.max(0, Math.min(100, gp)) + '%';
                bar.appendChild(fill);
                place.appendChild(bar);
                place.appendChild(el('span', 'gpu-place' + (full ? '' : ' partial'),
                    full ? '100 % GPU' : gp + ' % GPU / ' + (100 - gp) + ' % CPU'));
            }
            cell(tr, isNum(m.contextLength) ? String(m.contextLength) : '-');
            cell(tr, m.expiresAt ? eta(m.expiresAt) : '-');
            var act = el('td', 'gpu-act');
            var btn = el('button', 'gpu-modal-btn small', 'Ladda ur');
            btn.type = 'button';
            btn.setAttribute('aria-label', 'Ladda ur ' + (m.name || 'modell'));
            btn.addEventListener('click', function () { unloadModel(m.name, btn); });
            act.appendChild(btn);
            tr.appendChild(act);
            body.appendChild(tr);
        });
    }

    function renderProcs(procs) {
        var body = byId('gmProcs');
        if (!body) return;
        body.textContent = '';
        if (procs.length === 0) {
            var empty = el('tr');
            var td = cell(empty, 'Inga processer', 'gpu-empty');
            td.colSpan = 3;
            body.appendChild(empty);
            return;
        }
        procs.forEach(function (p) {
            var tr = el('tr');
            cell(tr, p.pid != null ? String(p.pid) : '-');
            cell(tr, p.name || '-', 'gpu-name');
            cell(tr, isNum(p.memMiB) ? (p.memMiB >= 1024 ? gb(p.memMiB) : Math.round(p.memMiB) + ' MiB') : '-');
            body.appendChild(tr);
        });
    }

    /* ---------- åtgärder ---------- */

    function showMsg(text, kind) {
        var node = byId('gmMsg');
        if (!node) return;
        node.textContent = text;
        node.className = 'gpu-modal-msg' + (kind ? ' ' + kind : '');
        if (msgTimer) clearTimeout(msgTimer);
        msgTimer = setTimeout(function () { node.textContent = ''; }, 7000);
    }

    function onUnloadAll() {
        var btn = byId('gmUnloadAll');
        if (!btn) return;
        if (!btn.dataset.confirm) {
            btn.dataset.confirm = '1';
            btn.textContent = 'Bekräfta: ladda ur alla';
            btn.classList.add('danger');
            confirmTimer = setTimeout(resetUnloadAll, 4000);
            return;
        }
        resetUnloadAll();
        unloadModel(null, btn);
    }

    function resetUnloadAll() {
        if (confirmTimer) { clearTimeout(confirmTimer); confirmTimer = null; }
        var btn = byId('gmUnloadAll');
        if (!btn) return;
        delete btn.dataset.confirm;
        btn.textContent = 'Ladda ur alla';
        btn.classList.remove('danger');
    }

    function unloadModel(name, btn) {
        if (btn) btn.disabled = true;
        showMsg(name ? 'Laddar ur ' + name + '…' : 'Laddar ur alla modeller…');
        fetch('/api/system/ollama/unload', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv' },
            body: JSON.stringify(name ? { model: name } : {})
        }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            showMsg('Urladdning begärd. Listan uppdateras inom några sekunder.', 'ok');
        }).catch(function () {
            showMsg('Kunde inte ladda ur. Kontrollera att Ollama svarar.', 'error');
        }).then(function () {
            if (btn && document.body.contains(btn)) btn.disabled = false;
        });
    }

    window.GpuModal = { open: openModal, close: closeModal };
    document.addEventListener('keydown', onKey);

    // ?gpu=1 i adressen öppnar modalen (gamla bokmärken till /gpu.html omdirigeras hit).
    try {
        if (new URLSearchParams(window.location.search).get('gpu') === '1') {
            setTimeout(function () {
                openModal();
                try { history.replaceState({}, document.title, window.location.pathname); } catch (e) { /* ignoreras */ }
            }, 50);
        }
    } catch (e) { /* ignoreras */ }
})();
