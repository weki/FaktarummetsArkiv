(function () {
    'use strict';

    var root = document.getElementById('gpuApp');
    if (!root) return;

    var nameEl = document.getElementById('gpuName');
    var vramEl = document.getElementById('gpuVram');
    var liveEl = document.getElementById('gpuLive');
    var liveText = document.getElementById('gpuLiveText');
    var rangeBtns = document.querySelectorAll('.gpu-range button');
    var kpiUtil = document.getElementById('kpiUtil');
    var kpiVram = document.getElementById('kpiVram');
    var kpiTemp = document.getElementById('kpiTemp');
    var kpiPow = document.getElementById('kpiPow');
    var kpiVramSub = document.getElementById('kpiVramSub');
    var kpiPowSub = document.getElementById('kpiPowSub');
    var chartUtil = document.getElementById('chartUtil');
    var chartMem = document.getElementById('chartMem');
    var banner = document.getElementById('gpuBanner');
    var bannerText = document.getElementById('gpuBannerText');
    var warnBox = document.getElementById('gpuWarnBox');
    var warnList = document.getElementById('gpuWarnList');
    var modelsBody = document.getElementById('gpuModelsBody');
    var procBody = document.getElementById('gpuProcBody');
    var disabledBox = document.getElementById('gpuDisabled');
    var chartsWrap = document.getElementById('gpuChartsWrap');

    var range = 60; // 2 min at 2s
    var lastData = null;

    rangeBtns.forEach(function (btn) {
        btn.addEventListener('click', function () {
            rangeBtns.forEach(function (b) { b.classList.remove('active'); });
            btn.classList.add('active');
            var m = parseInt(btn.getAttribute('data-min'), 10);
            if (m === 2) range = 60;
            else if (m === 5) range = 150;
            else if (m === 10) range = 300;
            render();
        });
    });

    function fmtTime(ts) {
        if (!ts) return '';
        var d = new Date(ts);
        return d.toLocaleTimeString('sv-SE');
    }

    function drawChart(svg, values, maxVal, dashed) {
        svg.innerHTML = '';
        var defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
        var grad = document.createElementNS('http://www.w3.org/2000/svg', 'linearGradient');
        grad.setAttribute('id', 'gpuGrad');
        grad.setAttribute('x1', '0');
        grad.setAttribute('y1', '0');
        grad.setAttribute('x2', '0');
        grad.setAttribute('y2', '1');
        var s1 = document.createElementNS('http://www.w3.org/2000/svg', 'stop');
        s1.setAttribute('offset', '0%');
        s1.setAttribute('stop-color', '#38bdf8');
        s1.setAttribute('stop-opacity', '0.4');
        var s2 = document.createElementNS('http://www.w3.org/2000/svg', 'stop');
        s2.setAttribute('offset', '100%');
        s2.setAttribute('stop-color', '#38bdf8');
        s2.setAttribute('stop-opacity', '0');
        grad.appendChild(s1);
        grad.appendChild(s2);
        defs.appendChild(grad);
        svg.appendChild(defs);

        var w = 520;
        var h = 180;
        var pad = 24;
        var plotW = w - pad * 2;
        var plotH = h - pad * 2;

        // grid
        for (var i = 0; i <= 4; i++) {
            var y = pad + (plotH / 4) * i;
            var line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            line.setAttribute('x1', pad);
            line.setAttribute('y1', y);
            line.setAttribute('x2', w - pad);
            line.setAttribute('y2', y);
            line.setAttribute('class', 'grid');
            svg.appendChild(line);
        }
        if (dashed && maxVal) {
            var dy = pad + plotH - (plotH * 0.95);
            var dline = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            dline.setAttribute('x1', pad);
            dline.setAttribute('y1', dy);
            dline.setAttribute('x2', w - pad);
            dline.setAttribute('y2', dy);
            dline.setAttribute('class', 'grid');
            dline.setAttribute('stroke-dasharray', '4 4');
            svg.appendChild(dline);
        }

        if (!values || values.length === 0) return;
        var pts = [];
        for (var j = 0; j < values.length; j++) {
            var x = pad + (plotW * j / Math.max(1, values.length - 1));
            var v = values[j];
            var mv = maxVal && maxVal > 0 ? maxVal : 100;
            var yv = pad + plotH - (plotH * Math.max(0, Math.min(mv, v)) / mv);
            pts.push(x + ',' + yv);
        }
        var area = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        var areaD = 'M' + pad + ',' + (pad + plotH) + ' L' + pts.join(' L') + ' L' + (pad + plotW) + ',' + (pad + plotH) + ' Z';
        area.setAttribute('d', areaD);
        area.setAttribute('class', 'area');
        svg.appendChild(area);

        var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        var d = 'M' + pts.join(' L');
        path.setAttribute('d', d);
        path.setAttribute('class', 'line');
        svg.appendChild(path);

        var last = values[values.length - 1];
        svg.setAttribute('role', 'img');
        svg.setAttribute('aria-label', 'Senaste värde ' + last);
    }

    function render() {
        var data = lastData;
        if (!data) return;
        var gpu = data.gpu || {};
        var models = data.models || [];
        var warns = data.warnings || [];

        if (gpu.reason === 'disabled') {
            if (disabledBox) disabledBox.style.display = 'block';
            if (chartsWrap) chartsWrap.style.display = 'none';
            if (banner) banner.style.display = 'none';
            if (warnBox) warnBox.style.display = 'none';
            if (nameEl) nameEl.textContent = 'GPU avstängd';
            if (vramEl) vramEl.textContent = '';
            if (liveEl) liveEl.classList.remove('stale');
            if (liveText) liveText.textContent = 'Avstängt';
            // still show models
            renderModels(models);
            renderProcs([]);
            return;
        }
        if (disabledBox) disabledBox.style.display = 'none';
        if (chartsWrap) chartsWrap.style.display = 'grid';

        if (nameEl) nameEl.textContent = gpu.name || 'Okänd GPU';
        var mt = typeof gpu.memTotalMiB === 'number' ? gpu.memTotalMiB : null;
        if (vramEl) vramEl.textContent = mt != null ? (mt / 1024).toFixed(0) + ' GB VRAM' : '';

        var stale = !!data._stale || gpu.available === false;
        if (liveEl) liveEl.classList.toggle('stale', stale);
        if (liveText) liveText.textContent = stale ? 'Ingen kontakt' : 'Live';

        if (gpu.available === false) {
            if (banner) banner.style.display = 'flex';
            if (bannerText) bannerText.textContent = 'Ingen kontakt med gpu-stats' + (gpu.reason ? ' (' + gpu.reason + ')' : '');
        } else {
            if (banner) banner.style.display = 'none';
        }

        if (warnBox) {
            if (warns.length > 0) {
                warnBox.style.display = 'flex';
                warnList.innerHTML = '';
                warns.forEach(function (w) {
                    var it = document.createElement('div');
                    it.className = 'gpu-warnbox-item';
                    var span = document.createElement('span');
                    span.textContent = w.message || w.code || '';
                    it.appendChild(span);
                    warnList.appendChild(it);
                });
            } else {
                warnBox.style.display = 'none';
            }
        }

        // KPIs
        if (kpiUtil) kpiUtil.textContent = typeof gpu.utilPct === 'number' ? gpu.utilPct + '%' : '-';
        if (kpiVram) {
            var mu = typeof gpu.memUsedMiB === 'number' ? gpu.memUsedMiB : null;
            kpiVram.textContent = (mu != null && mt != null) ? (mu / 1024).toFixed(1) + ' / ' + (mt / 1024).toFixed(1) + ' GB' : '-';
        }
        if (kpiVramSub) kpiVramSub.textContent = mt != null ? 'Totalt ' + (mt / 1024).toFixed(1) + ' GB' : '';
        if (kpiTemp) kpiTemp.textContent = typeof gpu.tempC === 'number' ? gpu.tempC + ' °C' : '-';
        if (kpiPow) {
            var pw = typeof gpu.powerW === 'number' ? gpu.powerW : null;
            var pl = typeof gpu.powerLimitW === 'number' ? gpu.powerLimitW : null;
            kpiPow.textContent = pw != null ? pw.toFixed(1) + ' W' : '-';
            if (kpiPowSub) kpiPowSub.textContent = pl != null ? 'Gräns ' + pl.toFixed(0) + ' W' : '';
        }

        // charts
        var hist = (data._history || []).slice(-range);
        var utilVals = hist.map(function (h) { return typeof h.util === 'number' ? h.util : null; }).filter(function (v) { return v !== null; });
        var memVals = hist.map(function (h) {
            var mu2 = typeof h.mem === 'number' ? h.mem : null;
            if (mu2 == null || mt == null || mt === 0) return null;
            return (mu2 / mt) * 100;
        }).filter(function (v) { return v !== null; });
        if (chartUtil) drawChart(chartUtil, utilVals.length ? utilVals : (typeof gpu.utilPct === 'number' ? [gpu.utilPct] : []), 100, false);
        if (chartMem) drawChart(chartMem, memVals.length ? memVals : [], 100, true);

        renderModels(models);
        renderProcs(gpu.processes || []);
    }

    function renderModels(models) {
        if (!modelsBody) return;
        modelsBody.innerHTML = '';
        if (!models || models.length === 0) {
            var tr = document.createElement('tr');
            var td = document.createElement('td');
            td.colSpan = 5;
            td.textContent = 'Inga laddade modeller';
            tr.appendChild(td);
            modelsBody.appendChild(tr);
            return;
        }
        models.forEach(function (m) {
            var tr = document.createElement('tr');
            var name = document.createElement('td');
            name.textContent = m.name || '';
            tr.appendChild(name);

            var size = document.createElement('td');
            var sb = typeof m.sizeBytes === 'number' ? m.sizeBytes : null;
            size.textContent = sb != null ? (sb / (1024*1024*1024)).toFixed(1) + ' GB' : '-';
            tr.appendChild(size);

            var place = document.createElement('td');
            var gpct = typeof m.gpuPct === 'number' ? m.gpuPct : null;
            var bar = document.createElement('div');
            bar.className = 'gpu-barcell';
            var b = document.createElement('div');
            b.className = 'gpu-bar' + (gpct != null && gpct < 100 ? ' warn' : '');
            var s = document.createElement('span');
            s.style.width = (gpct != null ? Math.max(0, Math.min(100, gpct)) : 0) + '%';
            b.appendChild(s);
            var lab = document.createElement('span');
            lab.className = 'gpu-pill' + (gpct != null && gpct < 100 ? ' warn' : '');
            if (gpct == null) lab.textContent = '-';
            else if (gpct >= 100) lab.textContent = '100 % GPU';
            else lab.textContent = gpct + ' % GPU / ' + (100 - gpct) + ' % CPU';
            bar.appendChild(b);
            bar.appendChild(lab);
            place.appendChild(bar);
            tr.appendChild(place);

            var ctx = document.createElement('td');
            ctx.textContent = typeof m.contextLength === 'number' ? m.contextLength : '-';
            tr.appendChild(ctx);

            var exp = document.createElement('td');
            exp.textContent = m.expiresAt ? m.expiresAt.replace('T', ' ').replace('Z', '') : '-';
            tr.appendChild(exp);

            modelsBody.appendChild(tr);
        });
    }

    function renderProcs(procs) {
        if (!procBody) return;
        procBody.innerHTML = '';
        if (!procs || procs.length === 0) {
            var tr = document.createElement('tr');
            var td = document.createElement('td');
            td.colSpan = 3;
            td.textContent = 'Inga processer';
            tr.appendChild(td);
            procBody.appendChild(tr);
            return;
        }
        procs.forEach(function (p) {
            var tr = document.createElement('tr');
            var pid = document.createElement('td');
            pid.textContent = p.pid != null ? p.pid : '-';
            tr.appendChild(pid);
            var name = document.createElement('td');
            name.textContent = p.name || '-';
            tr.appendChild(name);
            var mem = document.createElement('td');
            var mv = typeof p.memMiB === 'number' ? p.memMiB : null;
            mem.textContent = mv != null ? mv + ' MiB' : '-';
            tr.appendChild(mem);
            procBody.appendChild(tr);
        });
    }

    if (typeof window.GpuPoller !== 'undefined') {
        window.GpuPoller.subscribe(function (data) {
            lastData = data;
            render();
        });
    } else {
        fetch('/api/system/gpu', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                lastData = data;
                lastData._history = [];
                render();
            })
            .catch(function () { });
    }

    window.addEventListener('pagehide', function () {
        if (typeof window.GpuPoller !== 'undefined') {
            // can't easily unsubscribe without ref; ignore
        }
    });
})();
