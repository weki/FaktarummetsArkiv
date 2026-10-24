(() => {
    const API = '/api/settings';
    const content = document.getElementById('settingsContent');
    const nav = document.getElementById('settingsNav');
    const search = document.getElementById('settingsSearch');
    const status = document.getElementById('settingsStatus');
    const saveBtn = document.getElementById('saveBtn');
    const resetAllBtn = document.getElementById('resetAllBtn');
    const fileLabel = document.getElementById('settingsFile');
    const versionLabel = document.getElementById('settingsVersion');

    const sourceLabels = {default: 'Standard', env: 'Miljö', file: 'Fil', ui: 'UI'};
    const applyLabels = {
        LIVE: 'Direkt',
        MODEL_RELOAD: 'Modell laddas om',
        RESTART: 'Kräver omstart',
        REINDEX: 'Kräver omindexering'
    };

    let snapshot = null;
    let models = [];
    let dirty = new Map();
    let errors = new Map();
    let filter = '';

    function escapeHtml(value) {
        return String(value ?? '')
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#039;');
    }

    function setStatus(text, kind) {
        status.textContent = text || '';
        status.className = 'status' + (kind ? ' ' + kind : '');
    }

    function toast(text, kind) {
        const existing = document.querySelector('.settings-toast');
        if (existing) existing.remove();
        const element = document.createElement('div');
        element.className = 'settings-toast' + (kind ? ' ' + kind : '');
        element.textContent = text;
        document.body.appendChild(element);
        window.setTimeout(() => element.remove(), kind === 'error' ? 6500 : 3500);
    }

    function currentValue(setting) {
        return dirty.has(setting.key) ? dirty.get(setting.key) : setting.value;
    }

    function isDependencyBlocked(setting) {
        if (!setting.dependsOn) return false;
        const parent = snapshot.settings.find(item => item.key === setting.dependsOn);
        return parent ? !Boolean(currentValue(parent)) : false;
    }

    function render() {
        if (!snapshot) return;
        const groups = snapshot.groups.slice().sort((a, b) => a.order - b.order);
        content.innerHTML = '';
        nav.innerHTML = '';
        fileLabel.textContent = snapshot.file || 'settings.json';
        versionLabel.textContent = 'Version ' + snapshot.version;

        const query = filter.trim().toLowerCase();
        let visible = 0;

        for (const group of groups) {
            const settings = snapshot.settings.filter(setting => setting.group === group.id);
            const matching = settings.filter(setting => matches(setting, query));
            if (query && matching.length === 0) continue;
            visible += matching.length;

            const link = document.createElement('a');
            link.href = '#group-' + group.id;
            link.dataset.group = group.id;
            link.innerHTML = `<span>${escapeHtml(group.label)}</span><span>${matching.length}</span>`;
            nav.appendChild(link);

            const section = document.createElement('section');
            section.className = 'settings-group';
            section.id = 'group-' + group.id;
            section.innerHTML = `
                <div class="settings-group-head">
                    <h2>${escapeHtml(group.label)}</h2>
                    <p>${escapeHtml(group.description)}</p>
                </div>
                <div class="settings-rows"></div>`;
            const rows = section.querySelector('.settings-rows');
            for (const setting of matching) {
                rows.appendChild(renderSetting(setting));
            }
            content.appendChild(section);
        }

        if (visible === 0) {
            content.innerHTML = '<div class="settings-empty">Inga inställningar matchar sökningen.</div>';
        }
        applyDependencies();
        updateSaveButton();
        observeGroups();
    }

    function matches(setting, query) {
        if (!query) return true;
        return [setting.label, setting.key, setting.description, setting.group]
            .some(value => String(value || '').toLowerCase().includes(query));
    }

    function renderSetting(setting) {
        const row = document.createElement('article');
        row.className = 'setting-row';
        row.dataset.key = setting.key;
        if (dirty.has(setting.key)) row.classList.add('is-dirty');
        if (errors.has(setting.key)) row.classList.add('has-error');

        const source = dirty.has(setting.key) ? 'ui' : setting.source;
        const info = document.createElement('div');
        info.className = 'setting-info';
        info.innerHTML = `
            <div class="setting-title">
                <h3>${escapeHtml(setting.label)}</h3>
                <span class="badge badge-source-${escapeHtml(source)}">${escapeHtml(sourceLabels[source] || source)}</span>
                <span class="badge badge-${escapeHtml(String(setting.applyMode).toLowerCase())}">${escapeHtml(applyLabels[setting.applyMode] || setting.applyMode)}</span>
                ${setting.readOnly ? '<span class="badge badge-readonly">Skrivskyddad</span>' : ''}
            </div>
            <code class="setting-key">${escapeHtml(setting.key)}</code>
            <p class="setting-description">${escapeHtml(setting.description)}</p>
            ${setting.warning ? `<p class="setting-warning">${escapeHtml(setting.warning)}</p>` : ''}
            ${setting.dependsOn ? `<p class="setting-dependency">Kräver att ${escapeHtml(setting.dependsOn)} är på.</p>` : ''}
            <p class="setting-error" ${errors.has(setting.key) ? '' : 'hidden'}>${escapeHtml(errors.get(setting.key) || '')}</p>`;
        row.appendChild(info);

        const control = document.createElement('div');
        control.className = 'setting-control';
        control.appendChild(renderControl(setting));
        control.appendChild(renderMeta(setting));
        if (!setting.readOnly && (setting.overridden || dirty.has(setting.key))) {
            const reset = document.createElement('button');
            reset.type = 'button';
            reset.className = 'setting-reset';
            reset.textContent = 'Återställ till standard';
            reset.addEventListener('click', () => resetSetting(setting));
            control.appendChild(reset);
        }
        row.appendChild(control);
        return row;
    }

    function renderControl(setting) {
        if (setting.readOnly) {
            const readonly = document.createElement('div');
            readonly.className = 'setting-readonly';
            readonly.textContent = readableValue(setting);
            return readonly;
        }
        if (setting.type === 'BOOLEAN') {
            const label = document.createElement('label');
            label.className = 'setting-boolean';
            const input = document.createElement('input');
            input.type = 'checkbox';
            input.checked = Boolean(currentValue(setting));
            input.dataset.key = setting.key;
            input.addEventListener('change', () => change(setting, input.checked));
            const text = document.createElement('span');
            text.textContent = input.checked ? 'På' : 'Av';
            input.addEventListener('change', () => { text.textContent = input.checked ? 'På' : 'Av'; });
            label.append(input, text);
            return label;
        }
        if (setting.type === 'ENUM' || setting.type === 'MODEL') {
            const select = document.createElement('select');
            select.dataset.key = setting.key;
            const options = setting.type === 'MODEL' ? modelOptions(setting) : (setting.allowedValues || []);
            for (const option of options) {
                const element = document.createElement('option');
                element.value = option.value ?? option;
                element.textContent = option.label ?? option;
                select.appendChild(element);
            }
            const value = currentValue(setting);
            if (value !== null && value !== undefined && !options.some(option => String(option.value ?? option) === String(value))) {
                const element = document.createElement('option');
                element.value = String(value);
                element.textContent = String(value);
                select.appendChild(element);
            }
            select.value = value == null ? '' : String(value);
            select.addEventListener('change', () => change(setting, select.value));
            return select;
        }

        const input = document.createElement('input');
        input.dataset.key = setting.key;
        input.type = setting.type === 'INTEGER' || setting.type === 'DECIMAL' ? 'number' : 'text';
        if (setting.type === 'INTEGER') input.step = setting.step == null ? '1' : String(setting.step);
        if (setting.type === 'DECIMAL') input.step = setting.step == null ? '0.1' : String(setting.step);
        if (setting.min != null) input.min = String(setting.min);
        if (setting.max != null) input.max = String(setting.max);
        const value = currentValue(setting);
        input.value = value == null ? '' : String(value);
        input.addEventListener('input', () => {
            const next = input.value === '' ? null
                : (setting.type === 'INTEGER' || setting.type === 'DECIMAL') ? Number(input.value) : input.value;
            change(setting, next);
        });
        return input;
    }

    function modelOptions(setting) {
        const options = [];
        if (setting.key === 'rag.rerank.model' || setting.defaultValue === '') {
            options.push({value: '', label: 'Standard (chattmodellen)'});
        }
        for (const model of models) {
            options.push({value: model, label: model});
        }
        return options;
    }

    function readableValue(setting) {
        const value = currentValue(setting);
        if (value === null || value === undefined || value === '') return 'Ej satt';
        if (typeof value === 'boolean') return value ? 'På' : 'Av';
        return String(value);
    }

    function renderMeta(setting) {
        const meta = document.createElement('div');
        meta.className = 'setting-meta';
        const value = currentValue(setting);
        const effective = value === null || value === undefined || value === '' ? 'Ej satt' : String(value);
        meta.innerHTML = `<span>Effektivt: <strong>${escapeHtml(effective)}</strong></span>
            <span>Standard: <strong>${escapeHtml(readableDefault(setting.defaultValue))}</strong></span>`;
        return meta;
    }

    function readableDefault(value) {
        if (value === null || value === undefined || value === '') return 'Ej satt';
        if (typeof value === 'boolean') return value ? 'På' : 'Av';
        return String(value);
    }

    function change(setting, value) {
        errors.delete(setting.key);
        if (sameValue(value, setting.value)) {
            dirty.delete(setting.key);
        } else {
            dirty.set(setting.key, value);
        }
        const row = content.querySelector(`.setting-row[data-key="${cssEscape(setting.key)}"]`);
        if (row) {
            row.classList.toggle('is-dirty', dirty.has(setting.key));
            row.classList.remove('has-error');
            const error = row.querySelector('.setting-error');
            if (error) { error.hidden = true; error.textContent = ''; }
            const sourceBadge = row.querySelector('.badge-source-default, .badge-source-env, .badge-source-file, .badge-source-ui');
            if (sourceBadge) {
                const source = dirty.has(setting.key) ? 'ui' : setting.source;
                sourceBadge.className = `badge badge-source-${source}`;
                sourceBadge.textContent = sourceLabels[source] || source;
            }
        }
        applyDependencies();
        updateSaveButton();
        updateNavCounts();
    }

    function sameValue(left, right) {
        if (left === null || left === undefined || left === '') {
            return right === null || right === undefined || right === '';
        }
        if (typeof left === 'number' || typeof right === 'number') {
            return Number(left) === Number(right);
        }
        return String(left) === String(right);
    }

    function cssEscape(value) {
        if (window.CSS && CSS.escape) return CSS.escape(value);
        return String(value).replace(/["\\]/g, '\\$&');
    }

    function applyDependencies() {
        if (!snapshot) return;
        for (const setting of snapshot.settings) {
            if (!setting.dependsOn) continue;
            const row = content.querySelector(`.setting-row[data-key="${cssEscape(setting.key)}"]`);
            if (!row) continue;
            const blocked = isDependencyBlocked(setting);
            const control = row.querySelector('input, select, button.setting-reset');
            if (control) control.disabled = blocked;
            row.classList.toggle('is-blocked', blocked);
            const hint = row.querySelector('.setting-dependency');
            if (hint) hint.textContent = blocked
                ? `Aktiveras när ${setting.dependsOn} är på.`
                : `Kräver att ${setting.dependsOn} är på.`;
        }
    }

    function updateSaveButton() {
        saveBtn.disabled = dirty.size === 0;
        saveBtn.textContent = dirty.size === 0 ? 'Spara ändringar' : `Spara ${dirty.size} ändring${dirty.size === 1 ? '' : 'ar'}`;
    }

    function updateNavCounts() {
        if (!snapshot) return;
        for (const link of nav.querySelectorAll('a')) {
            const group = link.dataset.group;
            const count = snapshot.settings.filter(setting => setting.group === group
                && matches(setting, filter.trim().toLowerCase())).length;
            const badge = link.lastElementChild;
            if (badge) badge.textContent = count;
        }
    }

    async function load() {
        setStatus('Läser in…');
        try {
            const [settingsResponse, modelsResponse] = await Promise.all([
                fetch(API),
                fetch('/api/chat/models').catch(() => null)
            ]);
            if (!settingsResponse.ok) throw new Error('Kunde inte läsa inställningarna');
            snapshot = await settingsResponse.json();
            models = modelsResponse && modelsResponse.ok ? await modelsResponse.json().catch(() => []) : [];
            dirty.clear();
            errors.clear();
            setStatus('');
            render();
        } catch (error) {
            content.innerHTML = `<div class="settings-empty">${escapeHtml(error.message || 'Kunde inte läsa inställningarna.')}</div>`;
            setStatus('Fel', 'error');
        }
    }

    async function save() {
        if (!snapshot || dirty.size === 0) return;
        saveBtn.disabled = true;
        setStatus('Sparar…');
        errors.clear();
        const values = {};
        for (const [key, value] of dirty) values[key] = value;
        try {
            const response = await fetch(API, {
                method: 'POST',
                headers: {'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv'},
                body: JSON.stringify({values, expectedVersion: snapshot.version})
            });
            const body = await response.json().catch(() => ({}));
            if (response.status === 409) {
                setStatus('Konflikt', 'error');
                toast('Inställningarna ändrades av någon annan. Sidan laddas om.', 'error');
                window.setTimeout(load, 900);
                return;
            }
            if (!response.ok) {
                const message = body.error || 'Kunde inte spara inställningarna';
                if (body.field) errors.set(body.field, message);
                setStatus('Fel', 'error');
                toast(message, 'error');
                render();
                return;
            }
            snapshot = body;
            dirty.clear();
            setStatus('Sparat', 'ok');
            toast('Inställningarna sparades.');
            render();
        } catch (error) {
            setStatus('Fel', 'error');
            toast(error.message || 'Kunde inte spara inställningarna.', 'error');
        } finally {
            updateSaveButton();
        }
    }

    async function resetSetting(setting) {
        if (!snapshot) return;
        try {
            const response = await fetch(`${API}/${encodeURIComponent(setting.key)}`, {
                method: 'DELETE',
                headers: {'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv'},
                body: JSON.stringify({expectedVersion: snapshot.version})
            });
            const body = await response.json().catch(() => ({}));
            if (response.status === 409) {
                toast('Inställningarna ändrades av någon annan. Sidan laddas om.', 'error');
                window.setTimeout(load, 900);
                return;
            }
            if (!response.ok) throw new Error(body.error || 'Kunde inte återställa');
            snapshot = body;
            dirty.delete(setting.key);
            errors.delete(setting.key);
            setStatus('Återställt', 'ok');
            render();
        } catch (error) {
            toast(error.message || 'Kunde inte återställa inställningen.', 'error');
        }
    }

    async function resetAll() {
        if (!snapshot) return;
        if (!window.confirm('Återställa alla UI-överridningar till standardvärden?')) return;
        try {
            const response = await fetch(`${API}/reset`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv'},
                body: JSON.stringify({expectedVersion: snapshot.version})
            });
            const body = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(body.error || 'Kunde inte återställa');
            snapshot = body;
            dirty.clear();
            errors.clear();
            setStatus('Alla återställda', 'ok');
            toast('Alla UI-överridningar återställdes.');
            render();
        } catch (error) {
            toast(error.message || 'Kunde inte återställa inställningarna.', 'error');
        }
    }

    let observer;
    function observeGroups() {
        if (observer) observer.disconnect();
        if (!('IntersectionObserver' in window)) return;
        observer = new IntersectionObserver(entries => {
            const visible = entries.filter(entry => entry.isIntersecting)
                .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
            if (!visible) return;
            for (const link of nav.querySelectorAll('a')) {
                link.classList.toggle('active', link.dataset.group === visible.target.id.replace('group-', ''));
            }
        }, {rootMargin: '-96px 0px -65% 0px', threshold: [0.05, 0.25, 0.5]});
        content.querySelectorAll('.settings-group').forEach(section => observer.observe(section));
    }

    saveBtn.addEventListener('click', save);
    resetAllBtn.addEventListener('click', resetAll);
    search.addEventListener('input', () => {
        filter = search.value;
        render();
    });
    window.addEventListener('beforeunload', event => {
        if (dirty.size > 0) {
            event.preventDefault();
            event.returnValue = '';
        }
    });
    load();
})();
