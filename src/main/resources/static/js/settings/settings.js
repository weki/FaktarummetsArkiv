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

    const PER_ROLE = [
        {label: 'Validering', model: 'ollama.validator-model', ctx: 'ollama.validator-num-ctx'},
        {label: 'Förfining', model: 'ollama.refine-model'},
        {label: 'Sökgate', model: 'ollama.gate-model', ctx: 'ollama.gate-num-ctx'},
        {label: 'Omformulering', model: 'ollama.rewrite-model', ctx: 'ollama.rewrite-num-ctx'},
        {label: 'Följdfrågor', model: 'ollama.followup-model'},
        {label: 'Rerank', model: 'rag.rerank.model'}
    ];
    const PER_ROLE_MODEL_KEYS = new Set(PER_ROLE.map(role => role.model));
    const PER_ROLE_CTX_KEYS = new Set(PER_ROLE.filter(role => role.ctx).map(role => role.ctx));
    const PRIMARY_MODEL_KEYS = new Set(['ollama.model', 'ollama.chat-num-ctx', 'ollama.embedding-model']);

    /* Visningsstruktur. Bara presentation: nycklarna och backend är oförändrade.
       Inställningar som hör ihop samlas i ett markerat kluster med rubrik och förklaring.
       Nycklar som saknas här visas som vanligt i sin ursprungliga grupp. */
    const DISPLAY = {
        models: {
            order: 10, label: 'Modeller',
            description: 'Huvudmodell, kontext och thinking. Roller utan eget värde ärver huvudmodellen.',
            clusters: [
                {id: 'main', title: 'Huvudmodell och kontext',
                    note: 'Hör ihop: byte av modell eller kontextfönster laddar om modellen i Ollama.',
                    keys: ['ollama.model', 'ollama.chat-num-ctx']},
                {id: 'embedding', title: 'Embedding',
                    note: 'Måste ge samma vektordimension som Qdrant. Byte kräver omindexering.',
                    keys: ['ollama.embedding-model']},
                {id: 'thinking', title: 'Thinking per roll',
                    note: 'Låter modellen resonera före svaret. Av för hjälpsteg ger snabbare svar.',
                    keys: ['chat.thinking', 'ollama.validator-thinking', 'ollama.refine-thinking',
                        'ollama.gate-thinking', 'ollama.rewrite-thinking', 'ollama.followup-thinking']}
            ]
        },
        search: {
            order: 20, label: 'Sökning',
            description: 'Hur frågan blir en sökning och vilka källor som skickas till modellen.',
            clusters: [
                {id: 'query', title: 'Sökfråga',
                    note: 'Hör ihop: sökgaten avgör om sökning behövs och omformuleringen gör frågan fristående.',
                    keys: ['chat.smart-search', 'rag.gate.mode', 'chat.rewrite-query', 'rag.search-language']},
                {id: 'sources', title: 'Urval av källor',
                    note: 'Hör ihop: antal träffar, grannstycken och källtecken delar på samma utrymme i modellens kontext.',
                    keys: ['qdrant.top-k', 'rag.similarity-threshold', 'rag.neighbor-chunks', 'rag.max-source-chars']},
                {id: 'hybrid', title: 'Hybridsökning',
                    note: 'Lägger nyckelordssökning till vektorsökningen. Hjälper på exakta termer.',
                    keys: ['rag.hybrid.enabled', 'rag.hybrid.keyword-limit', 'rag.hybrid.rrf-k']},
                {id: 'rerank', title: 'Rerankning',
                    note: 'En modell rangordnar om kandidaterna. Kostar en extra modellkörning per fråga.',
                    keys: ['rag.rerank.enabled', 'rag.rerank.candidates', 'rag.rerank.max-chars']}
            ]
        },
        answer: {
            order: 30, label: 'Svar',
            description: 'Svarsstil, historik och kontroll av att svaret stöds av källorna.',
            clusters: [
                {id: 'style', title: 'Svarsstil',
                    keys: ['chat.mode', 'chat.answer-language', 'chat.answer-temperature']},
                {id: 'history', title: 'Historik',
                    note: 'Tidigare meddelanden som följer med i frågan.',
                    keys: ['rag.max-history', 'rag.max-history-chars']},
                {id: 'grounding', title: 'Kontroll av svaret',
                    note: 'Valideringen jämför svaret med källorna. Modell och thinking för den väljs under Modeller.',
                    keys: ['chat.validate-grounding']}
            ]
        },
        gpu: {
            order: 40, label: 'GPU',
            description: 'GPU- och modellstatus från Ollama-maskinen, historik och VRAM-budget.',
            clusters: [
                {id: 'connection', title: 'Anslutning',
                    note: 'Adressen till gpu-stats på Ollama-maskinen.',
                    keys: ['gpu.stats-url', 'gpu.poll-interval-ms']},
                {id: 'history', title: 'Historik',
                    note: 'Sparas på servern så att graferna överlever omladdning och omstart.',
                    keys: ['gpu.history-enabled', 'gpu.history-hours', 'gpu.history-sample-seconds']},
                {id: 'budget', title: 'VRAM-budget',
                    keys: ['ollama.vram-gb']},
                {id: 'restart', title: 'Omstart av Ollama',
                    note: 'Avstängd som standard. Kräver token och en säker sidovagn.',
                    keys: ['gpu.restart-enabled', 'gpu.restart-token']}
            ]
        },
        indexing: {
            order: 50, label: 'Indexering',
            description: 'Hur dokument delas upp. Gäller nya dokument; gamla behöver indexeras om.',
            clusters: [
                {id: 'chunking', title: 'Chunkning',
                    note: 'Hör ihop: överlappet ska vara klart mindre än chunkstorleken.',
                    keys: ['rag.chunk-size', 'rag.chunk-overlap']}
            ]
        },
        limits: {
            order: 60, label: 'Gränser och sökvägar',
            description: 'Uppladdning, requeststorlek och arkivsökvägar. Ändras i konfigurationen.',
            clusters: [
                {id: 'upload', title: 'Uppladdning',
                    keys: ['spring.servlet.multipart.max-file-size', 'spring.servlet.multipart.max-request-size',
                        'server.tomcat.max-swallow-size', 'spring.codec.max-in-memory-size']},
                {id: 'paths', title: 'Sökvägar',
                    keys: ['archive.upload-dir', 'archive.pdf-roots']}
            ]
        },
        infrastructure: {
            order: 70, label: 'Infrastruktur',
            description: 'Anslutningar och vektorinställningar. Ändras i konfigurationen och kräver omstart.',
            clusters: [
                {id: 'connections', title: 'Anslutningar',
                    keys: ['ollama.base-url', 'ollama.timeout', 'qdrant.base-url', 'qdrant.collection', 'qdrant.timeout']},
                {id: 'vectors', title: 'Vektorer och embedding',
                    note: 'Hör ihop med embeddingmodellen: ändring kräver omindexering.',
                    keys: ['qdrant.vector-size', 'qdrant.distance', 'qdrant.query-prefix', 'qdrant.passage-prefix']}
            ]
        }
    };
    const ADVANCED = '__advanced';
    const PLACEMENT = new Map();
    for (const [groupId, def] of Object.entries(DISPLAY)) {
        for (const cluster of def.clusters) {
            for (const key of cluster.keys) PLACEMENT.set(key, {group: groupId, cluster: cluster.id});
        }
    }

    function placement(setting) {
        if (PER_ROLE_MODEL_KEYS.has(setting.key) || PER_ROLE_CTX_KEYS.has(setting.key)) {
            return {group: 'models', cluster: ADVANCED};
        }
        return PLACEMENT.get(setting.key) || {group: setting.group, cluster: null};
    }

    function viewGroups() {
        const result = new Map();
        for (const group of snapshot.groups) {
            result.set(group.id, {id: group.id, label: group.label, description: group.description, order: group.order});
        }
        for (const [id, def] of Object.entries(DISPLAY)) {
            result.set(id, {id, label: def.label, description: def.description, order: def.order});
        }
        return Array.from(result.values()).sort((a, b) => a.order - b.order);
    }

    function groupSettings(groupId, query) {
        const matching = snapshot.settings.filter(setting => {
            const place = placement(setting);
            return place.group === groupId && place.cluster !== ADVANCED && matches(setting, query);
        });
        const advanced = groupId === 'models'
            ? advancedSettings().filter(setting => !query || matches(setting, query)) : [];
        return {matching, advanced};
    }

    function renderCluster(def, items) {
        const wrap = document.createElement('div');
        wrap.className = 'setting-cluster';
        wrap.dataset.cluster = def.id;
        wrap.setAttribute('role', 'group');
        const head = document.createElement('div');
        head.className = 'cluster-head';
        const title = document.createElement('h3');
        title.className = 'cluster-title';
        title.textContent = def.title;
        title.id = 'cluster-' + def.id + '-' + Math.random().toString(36).slice(2, 7);
        wrap.setAttribute('aria-labelledby', title.id);
        head.appendChild(title);
        if (items.length > 1) {
            const pill = document.createElement('span');
            pill.className = 'cluster-pill';
            pill.textContent = 'Hör ihop';
            head.appendChild(pill);
        }
        if (def.note) {
            const note = document.createElement('p');
            note.className = 'cluster-note';
            note.textContent = def.note;
            head.appendChild(note);
        }
        wrap.appendChild(head);
        const body = document.createElement('div');
        body.className = 'cluster-rows';
        for (const item of items) body.appendChild(renderSetting(item));
        wrap.appendChild(body);
        return wrap;
    }

    function renderRows(rows, groupId, matching) {
        const def = DISPLAY[groupId];
        const placed = new Set();
        if (def) {
            for (const cluster of def.clusters) {
                const items = cluster.keys.map(findSetting).filter(item => item && matching.includes(item));
                if (items.length === 0) continue;
                rows.appendChild(renderCluster(cluster, items));
                items.forEach(item => placed.add(item));
            }
        }
        for (const setting of matching) {
            if (!placed.has(setting)) rows.appendChild(renderSetting(setting));
        }
    }

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
        content.innerHTML = '';
        nav.innerHTML = '';
        fileLabel.textContent = snapshot.file || 'settings.json';
        versionLabel.textContent = 'Version ' + snapshot.version;

        const query = filter.trim().toLowerCase();
        let visible = 0;

        for (const group of viewGroups()) {
            const {matching, advanced} = groupSettings(group.id, query);
            if (matching.length === 0 && advanced.length === 0) continue;
            visible += matching.length + advanced.length;

            const link = document.createElement('a');
            link.href = '#group-' + group.id;
            link.dataset.group = group.id;
            link.innerHTML = `<span>${escapeHtml(group.label)}</span><span>${matching.length + advanced.length}</span>`;
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
            renderRows(rows, group.id, matching);
            if (group.id === 'models') {
                const warnings = modelWarnings();
                if (warnings.length > 0) {
                    rows.appendChild(renderWarnings(warnings));
                }
                if (advanced.length > 0) {
                    rows.appendChild(renderAdvanced(advanced, query));
                }
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

    function findSetting(key) {
        return snapshot ? snapshot.settings.find(setting => setting.key === key) : null;
    }

    function advancedSettings() {
        const keys = [];
        for (const role of PER_ROLE) {
            keys.push(role.model);
            if (role.ctx) keys.push(role.ctx);
        }
        return keys.map(findSetting).filter(Boolean);
    }

    function isOwn(setting) {
        if (!setting) return false;
        const value = currentValue(setting);
        return value !== null && value !== undefined && String(value).trim() !== '';
    }

    function chatModelValue() {
        const setting = findSetting('ollama.model');
        const value = setting ? currentValue(setting) : '';
        return value === null || value === undefined ? '' : String(value);
    }

    function chatCtxValue() {
        const setting = findSetting('ollama.chat-num-ctx');
        const value = setting ? Number(currentValue(setting)) : 0;
        return Number.isFinite(value) && value > 0 ? value : 16384;
    }

    function effectiveValue(setting) {
        if (!setting) return '';
        const own = currentValue(setting);
        if (PER_ROLE_MODEL_KEYS.has(setting.key)) {
            if (isOwn(setting)) return String(own);
            if (setting.key === 'ollama.followup-model') {
                const refine = findSetting('ollama.refine-model');
                return isOwn(refine) ? String(currentValue(refine)) : chatModelValue();
            }
            return chatModelValue();
        }
        if (PER_ROLE_CTX_KEYS.has(setting.key)) {
            return isOwn(setting) ? own : chatCtxValue();
        }
        return own;
    }

    function renderWarnings(warnings) {
        const box = document.createElement('div');
        box.className = 'settings-warning-box';
        const title = document.createElement('h4');
        title.textContent = 'Varningar';
        box.appendChild(title);
        for (const warning of warnings) {
            const text = document.createElement('p');
            text.textContent = warning;
            box.appendChild(text);
        }
        return box;
    }

    function modelWarnings() {
        const chatModel = chatModelValue();
        const chatCtx = chatCtxValue();
        const byModel = new Map();
        for (const role of PER_ROLE) {
            const modelSetting = findSetting(role.model);
            const model = isOwn(modelSetting) ? String(currentValue(modelSetting)).trim() : chatModel.trim();
            if (!model) continue;
            const ctxSetting = role.ctx ? findSetting(role.ctx) : null;
            const ctx = ctxSetting && isOwn(ctxSetting) ? Number(currentValue(ctxSetting)) : chatCtx;
            if (!byModel.has(model)) byModel.set(model, new Set());
            byModel.get(model).add(ctx);
        }
        const warnings = [];
        for (const [model, contexts] of byModel) {
            if (contexts.size > 1) {
                warnings.push(`Olika kontext för samma modell (${model}): ${Array.from(contexts).join(', ')}. `
                    + 'Olika kontext för samma modell ger omladdning mellan anropen. '
                    + 'Sätt samma värde eller låt rollen ärva.');
            }
        }

        const configured = new Set(byModel.keys());
        if (chatModel.trim()) configured.add(chatModel.trim());
        const embeddingSetting = findSetting('ollama.embedding-model');
        const embedding = embeddingSetting ? String(currentValue(embeddingSetting) || '').trim() : '';
        if (embedding) configured.add(embedding);

        const budgetSetting = findSetting('ollama.vram-gb');
        const budget = budgetSetting ? Number(currentValue(budgetSetting)) : 0;
        if (configured.size > 2 && Number.isFinite(budget) && budget > 0) {
            warnings.push(`Fler än två unika modeller är konfigurerade (${Array.from(configured).join(', ')}). `
                + `Kontrollera att GPU-budgeten på ${budget} GB räcker.`);
        }

        if (models.length > 0) {
            const missing = Array.from(configured).filter(model => !models.includes(model));
            if (missing.length > 0) {
                warnings.push(`Dessa modeller är inte installerade i Ollama: ${missing.join(', ')}. `
                    + 'Inställningarna kan sparas ändå, men anropen misslyckas tills modellerna finns.');
            }
        }
        return warnings;
    }

    function renderAdvanced(advanced, query) {
        const details = document.createElement('details');
        details.className = 'settings-advanced';
        details.open = Boolean(query);
        const summary = document.createElement('summary');
        const ownCount = PER_ROLE.reduce((count, role) => count
            + (isOwn(findSetting(role.model)) ? 1 : 0)
            + (role.ctx && isOwn(findSetting(role.ctx)) ? 1 : 0), 0);
        summary.textContent = 'Avancerat: modell per roll'
            + (ownCount > 0 ? ` (${ownCount} eget värde)` : '');
        details.appendChild(summary);
        const intro = document.createElement('p');
        intro.className = 'settings-advanced-intro';
        intro.textContent = 'Låt rollerna ärva huvudmodellen och kontextfönstret om du inte har ett särskilt skäl att avvika.';
        details.appendChild(intro);
        const grid = document.createElement('div');
        grid.className = 'settings-advanced-roles';
        for (const role of PER_ROLE) {
            const modelSetting = findSetting(role.model);
            const ctxSetting = role.ctx ? findSetting(role.ctx) : null;
            if (!modelSetting || (query && !matches(modelSetting, query)
                    && !(ctxSetting && matches(ctxSetting, query)))) {
                continue;
            }
            const wrap = document.createElement('div');
            wrap.className = 'settings-advanced-role';
            const heading = document.createElement('h4');
            heading.textContent = role.label;
            wrap.appendChild(heading);
            if (role.model === 'ollama.gate-model' || role.model === 'ollama.rewrite-model') {
                const tip = document.createElement('p');
                tip.className = 'settings-role-tip';
                tip.textContent = 'Tips: en liten modell (under ca 4B parametrar) räcker oftast '
                    + 'för det här steget och lämnar mer VRAM till huvudmodellen.';
                wrap.appendChild(tip);
            }
            wrap.appendChild(renderSetting(modelSetting));
            if (ctxSetting) wrap.appendChild(renderSetting(ctxSetting));
            grid.appendChild(wrap);
        }
        details.appendChild(grid);
        return details;
    }

    function renderSetting(setting) {
        const row = document.createElement('article');
        row.className = 'setting-row';
        row.dataset.key = setting.key;
        if (setting.dependsOn) row.classList.add('is-dependent');
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
            select.setAttribute('aria-label', setting.label);
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
            select.addEventListener('change', () => change(setting, select.value === '' ? null : select.value));
            const label = document.createElement('label');
            label.className = 'setting-field';
            const caption = document.createElement('span');
            caption.textContent = setting.label;
            label.append(caption, select);
            return label;
        }

        const input = document.createElement('input');
        input.dataset.key = setting.key;
        input.type = setting.type === 'INTEGER' || setting.type === 'DECIMAL' ? 'number' : 'text';
        if (setting.type === 'INTEGER') input.step = setting.step == null ? '1' : String(setting.step);
        if (setting.type === 'DECIMAL') input.step = setting.step == null ? '0.1' : String(setting.step);
        if (setting.min != null) input.min = String(setting.min);
        if (setting.max != null) input.max = String(setting.max);
        input.setAttribute('aria-label', setting.label);
        const value = currentValue(setting);
        input.value = value == null ? '' : String(value);
        if (PER_ROLE_CTX_KEYS.has(setting.key) && !isOwn(setting)) {
            input.placeholder = `ärver ${chatCtxValue()}`;
        }
        input.addEventListener('input', () => {
            const next = input.value === '' ? null
                : (setting.type === 'INTEGER' || setting.type === 'DECIMAL') ? Number(input.value) : input.value;
            change(setting, next);
        });
        return input;
    }

    function modelOptions(setting) {
        const options = [];
        if (PER_ROLE_MODEL_KEYS.has(setting.key)) {
            options.push({value: '', label: 'Samma som huvudmodellen'});
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
        const value = effectiveValue(setting);
        const perRole = PER_ROLE_MODEL_KEYS.has(setting.key) || PER_ROLE_CTX_KEYS.has(setting.key);
        const own = !perRole || isOwn(setting);
        const effective = value === null || value === undefined || value === ''
            ? 'Ej satt' : String(value);
        const inherited = own ? '' : ' (ärvd)';
        const fallback = perRole && readableDefault(setting.defaultValue) === 'Ej satt'
            ? 'ärvd' : readableDefault(setting.defaultValue);
        meta.innerHTML = `<span>Effektivt: <strong>${escapeHtml(effective)}</strong>${escapeHtml(inherited)}</span>
            <span>Standard: <strong>${escapeHtml(fallback)}</strong></span>`;
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
        updateDerived();
    }

    function updateDerived() {
        if (!snapshot) return;
        for (const setting of snapshot.settings) {
            const row = content.querySelector(`.setting-row[data-key="${cssEscape(setting.key)}"]`);
            if (!row) continue;
            const meta = row.querySelector('.setting-meta');
            if (meta) meta.replaceWith(renderMeta(setting));
            if (PER_ROLE_CTX_KEYS.has(setting.key)) {
                const input = row.querySelector('input');
                if (input) {
                    if (isOwn(setting)) {
                        input.removeAttribute('placeholder');
                    } else {
                        input.placeholder = `ärver ${chatCtxValue()}`;
                    }
                }
            }
        }
        const rows = content.querySelector('#group-models .settings-rows');
        if (!rows) return;
        const existing = rows.querySelector('.settings-warning-box');
        if (existing) existing.remove();
        const warnings = modelWarnings();
        if (warnings.length > 0) {
            const box = renderWarnings(warnings);
            const advanced = rows.querySelector('.settings-advanced');
            if (advanced) {
                rows.insertBefore(box, advanced);
            } else {
                rows.appendChild(box);
            }
        }
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
        const query = filter.trim().toLowerCase();
        for (const link of nav.querySelectorAll('a')) {
            const {matching, advanced} = groupSettings(link.dataset.group, query);
            const badge = link.lastElementChild;
            if (badge) badge.textContent = matching.length + advanced.length;
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
        if (!window.confirm('Återställa alla UI-överstyrningar till standardvärden?')) return;
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
            toast('Alla UI-överstyrningar återställdes.');
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
