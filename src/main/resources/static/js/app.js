const API = '/api/chat';
    const DOC_API = '/api/documents';

    const messages = document.getElementById('messages');
    const chatForm = document.getElementById('chatForm');
    const questionInput = document.getElementById('question');
    const sendBtn = document.getElementById('sendBtn');
    const newThreadBtn = document.getElementById('newThreadBtn');
    const threadList = document.getElementById('threadList');
    const settingsBtn = document.getElementById('settingsBtn');
    const settingsOverlay = document.getElementById('settingsOverlay');
    const settingsClose = document.getElementById('settingsClose');
    const settingsForm = document.getElementById('settingsForm');
    const settingsStatus = document.getElementById('settingsStatus');
    const settingsSave = document.getElementById('settingsSave');
    const collectionList = document.getElementById('collectionList');
    const documentsBtn = document.getElementById('documentsBtn');
    const documentsPage = document.getElementById('documentsPage');
    const documentsClose = document.getElementById('documentsClose');
    const uploadForm = document.getElementById('uploadForm');
    const uploadBtn = document.getElementById('uploadBtn');
    const uploadCollection = document.getElementById('uploadCollection');
    const newCollectionField = document.getElementById('newCollectionField');
    const newCollectionName = document.getElementById('newCollectionName');
    const uploadTags = document.getElementById('uploadTags');
    const uploadFolderBtn = document.getElementById('uploadFolderBtn');
    const uploadFolderInput = document.getElementById('uploadFolderInput');
    const uploadProgress = document.getElementById('uploadProgress');
    const uploadProgressLabel = document.getElementById('uploadProgressLabel');
    const uploadProgressPct = document.getElementById('uploadProgressPct');
    const uploadProgressFill = document.getElementById('uploadProgressFill');
    const uploadProgressDetail = document.getElementById('uploadProgressDetail');
    const tagSuggestions = document.getElementById('tagSuggestions');
    const documentsStatus = document.getElementById('documentsStatus');
    const docList = document.getElementById('docList');
    const docsSelectAll = document.getElementById('docsSelectAll');
    const docsBulkDownload = document.getElementById('docsBulkDownload');
    const docsBulkDelete = document.getElementById('docsBulkDelete');
    const docsBulkCount = document.getElementById('docsBulkCount');
    const selectedDocIds = new Set(JSON.parse(localStorage.getItem('selectedDocIds') || '[]'));
    function persistSelectedDocIds() { localStorage.setItem('selectedDocIds', JSON.stringify(Array.from(selectedDocIds))); }
    const docsSearch = document.getElementById('docsSearch');
    const docsFilterCollection = document.getElementById('docsFilterCollection');
    const docsFilterTag = document.getElementById('docsFilterTag');
    const docsSort = document.getElementById('docsSort');
    const collectionsStatus = document.getElementById('collectionsStatus');
    const collectionsSave = document.getElementById('collectionsSave');
    const newCollectionInput = document.getElementById('newCollectionInput');
    const createCollectionBtn = document.getElementById('createCollectionBtn');
    const docOverlay = document.getElementById('docOverlay');
    const docModalClose = document.getElementById('docModalClose');
    const docModalTitle = document.getElementById('docModalTitle');
    const docModalMeta = document.getElementById('docModalMeta');
    const docModalActions = document.getElementById('docModalActions');
    const docModalFrame = document.getElementById('docModalFrame');
    const docModalText = document.getElementById('docModalText');

    const threadDocOverlay = document.getElementById('threadDocOverlay');
    const threadDocClose = document.getElementById('threadDocClose');
    const threadDocCollectionList = document.getElementById('threadDocCollectionList');
    const threadDocList = document.getElementById('threadDocList');
    const threadDocSearch = document.getElementById('threadDocSearch');
    const threadDocFilterCollection = document.getElementById('threadDocFilterCollection');
    const threadDocSave = document.getElementById('threadDocSave');
    const threadDocStatus = document.getElementById('threadDocStatus');

    let threadDocTargetId = null;

    const STORAGE_KEY = 'fakturummets_arkiv_rag_threads';

    let threads = loadThreads();
    let activeId = threads.length ? threads[0].id : null;

    function loadThreads() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            const parsed = raw ? JSON.parse(raw) : [];
            return Array.isArray(parsed) ? parsed : [];
        } catch (e) {
            return [];
        }
    }

    function saveThreads() {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(threads));
        } catch (e) { /* ignore quota errors */ }
    }

    function activeThread() {
        return threads.find(t => t.id === activeId) || null;
    }

    function threadTitle(thread) {
        const firstUser = thread.messages.find(m => m.role === 'user');
        if (!firstUser) return 'Ny tråd';
        return firstUser.text.length > 34 ? firstUser.text.slice(0, 34) + '…' : firstUser.text;
    }

    function createThread() {
        const thread = {id: crypto.randomUUID(), messages: [], collections: [], documentIds: []};
        threads.unshift(thread);
        activeId = thread.id;
        saveThreads();
        renderThreadList();
        renderMessages();
        questionInput.focus();
        return thread;
    }

    function selectThread(id) {
        activeId = id;
        renderThreadList();
        renderMessages();
        questionInput.focus();
    }

    function deleteThread(id) {
        const thread = threads.find(t => t.id === id);
        if (!thread) return;
        if (!confirm('Ta bort tråden "' + threadTitle(thread) + '"?')) return;

        threads = threads.filter(t => t.id !== id);
        if (activeId === id) {
            activeId = threads.length ? threads[0].id : null;
        }
        saveThreads();

        if (!threads.length) {
            createThread();
            return;
        }
        renderThreadList();
        renderMessages();
        questionInput.focus();
    }

    function renderThreadList() {
        threadList.innerHTML = '';
        for (const thread of threads) {
            const row = document.createElement('div');
            row.className = 'thread-row';

            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'thread-item' + (thread.id === activeId ? ' active' : '');
            btn.textContent = threadTitle(thread);
            btn.title = threadTitle(thread);
            btn.addEventListener('click', () => selectThread(thread.id));

            const docBtn = document.createElement('button');
            docBtn.type = 'button';
            docBtn.className = 'thread-doc-btn';
            docBtn.title = 'Välj dokument för denna tråd';
            docBtn.innerHTML = '📄';
            const hasDocSelection = (thread.collections && thread.collections.length) || (thread.documentIds && thread.documentIds.length);
            if (hasDocSelection) docBtn.classList.add('has-selection');
            docBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                openThreadDocSelector(thread.id);
            });

            const del = document.createElement('button');
            del.type = 'button';
            del.className = 'thread-delete';
            del.textContent = '×';
            del.title = 'Ta bort tråd';
            del.addEventListener('click', (e) => {
                e.stopPropagation();
                deleteThread(thread.id);
            });

            row.appendChild(btn);
            row.appendChild(docBtn);
            row.appendChild(del);
            threadList.appendChild(row);
        }
    }

    function renderMessages() {
        messages.innerHTML = '';
        const thread = activeThread();
        if (!thread || !thread.messages.length) {
            const div = document.createElement('div');
            div.className = 'msg assistant';
            div.textContent = 'Hej! Ställ en fråga. Svaren bygger på dokumenten i ditt lokala arkiv.';
            messages.appendChild(div);
            return;
        }
        for (const m of thread.messages) {
            renderMessage(m);
        }
        messages.scrollTop = messages.scrollHeight;
    }

    function renderMessage(m) {
        if (m.role === 'assistant') {
            const ui = buildAssistantLayout();
            const direct = m.searched === false;
            if (direct) {
                ui.progress.setDirect();
                ui.progress.finish();
            } else {
                ui.progress.finish();
            }
            ui.thoughtsBox.open = false;
            if (m.thoughts) ui.thoughts.textContent = m.thoughts;
            if (m.html) {
                ui.answer.classList.add('md');
                ui.answer.innerHTML = m.html;
            } else {
                ui.answer.textContent = m.text || '';
            }
            showFinalAnswer(ui);
            if (direct) {
                if (ui.judge) ui.judge.remove();
                if (!m.thoughts && ui.thoughtsBox) ui.thoughtsBox.remove();
            } else if (m.validation) {
                completeJudge(ui, m.validation);
            } else {
                ui.judge.classList.remove('waiting');
                if (ui.judgeStatus) ui.judgeStatus.textContent = 'Ingen verifiering';
            }
            if (m.refined) appendRefinedNote(ui.div);
            if (m.sources) appendSources(ui.div, m.sources, m.searchQuery);
            if (m.originalText) appendOriginalAnswer(ui.div, m.originalText);
            if (m.followUps) appendFollowUps(ui.div, m.followUps);
            messages.appendChild(ui.div);
        } else {
            const div = document.createElement('div');
            div.className = 'msg ' + m.role;
            div.textContent = m.text;
            messages.appendChild(div);
        }
    }

    function addMessage(text, role, sources) {
        const thread = activeThread();
        if (thread && role !== 'error') {
            thread.messages.push({role, text, sources});
            saveThreads();
            renderThreadList();
        }
        renderMessage({role, text, sources});
        messages.scrollTop = messages.scrollHeight;
    }

    function appendSources(container, sources, searchQuery) {
        if (!sources || !sources.length) return;
        const hasFlags = sources.some(source => Object.prototype.hasOwnProperty.call(source, 'cited'));
        const cited = hasFlags ? sources.filter(source => source.cited === true) : sources.slice();
        const uncited = hasFlags ? sources.filter(source => source.cited !== true) : [];
        const details = document.createElement('details');
        details.className = 'sources';
        const summary = document.createElement('summary');
        summary.textContent = cited.length
            ? 'Källor (' + cited.length + ')'
            : 'Inga källor citerades i svaret';
        details.appendChild(summary);
        if (searchQuery) {
            const query = document.createElement('div');
            query.className = 'sources-query';
            query.textContent = 'Sökfråga: ' + searchQuery;
            details.appendChild(query);
        }
        for (const source of cited) {
            appendSourceItem(details, source);
        }
        if (uncited.length) {
            const rest = document.createElement('details');
            rest.className = 'sources-uncited';
            const restSummary = document.createElement('summary');
            restSummary.textContent = 'Övriga hämtade källor (' + uncited.length + ')';
            rest.appendChild(restSummary);
            for (const source of uncited) {
                appendSourceItem(rest, source);
            }
            details.appendChild(rest);
        }
        container.appendChild(details);
    }

    function appendSourceItem(parent, source) {
        const item = document.createElement('details');
        item.className = 'source-item';
        if (source.citationNumber) {
            item.dataset.sourceNumber = String(source.citationNumber);
            item.id = 'source-' + source.citationNumber;
        }

        const head = document.createElement('summary');
        const title = (source.citationNumber ? '[' + source.citationNumber + '] ' : '')
            + (source.title || source.name || source.source || 'Källa');
        const bits = [];
        if (source.collection) bits.push(source.collection);
        if (source.page !== undefined && source.page !== null) bits.push('sida ' + source.page);
        if (typeof source.score === 'number') bits.push(source.score.toFixed(2));
        head.textContent = title + (bits.length ? ' · ' + bits.join(' · ') : '');
        item.appendChild(head);

        const body = document.createElement('div');
        body.className = 'source-text';
        body.textContent = source.text || source.content || '';
        item.appendChild(body);

        const ref = {
            title: title,
            meta: bits.join(' · '),
            documentId: source.documentId,
            text: source.text || source.content || ''
        };
        const pdf = docPdfUrl(ref);
        const download = docDownloadUrl(ref);
        if (pdf || download) {
            const actions = document.createElement('div');
            actions.className = 'source-actions';
            if (pdf || ref.documentId) {
                const view = document.createElement('button');
                view.type = 'button';
                view.className = 'link-btn';
                view.textContent = 'Visa';
                view.addEventListener('click', () => openDocumentPopup(ref));
                actions.appendChild(view);
            }
            if (download) {
                const link = document.createElement('a');
                link.href = download;
                link.textContent = 'Ladda ner';
                actions.appendChild(link);
            }
            item.appendChild(actions);
        }

        parent.appendChild(item);
    }

    function appendRefinedNote(container) {
        const note = document.createElement('div');
        note.className = 'refined-note';
        note.textContent = 'Omformulerad för att grundas i källorna.';
        container.appendChild(note);
    }

    function appendOriginalAnswer(container, text) {
        if (!text) return;
        const details = document.createElement('details');
        details.className = 'original-answer';
        const summary = document.createElement('summary');
        summary.textContent = 'Ursprungligt svar (innan omformulering)';
        details.appendChild(summary);
        const body = document.createElement('div');
        body.className = 'answer';
        body.textContent = text;
        details.appendChild(body);
        container.appendChild(details);
    }

    function appendValidation(container, validation) {
        if (!validation) return;
        let cls = 'unknown';
        let label = 'Ej verifierad';
        if (validation.verdict === 'SUPPORTED' || validation.supported === true) {
            cls = 'ok';
            label = 'Verifierad mot källor';
        } else if (validation.verdict === 'PARTIALLY_SUPPORTED') {
            cls = 'warn';
            label = 'Delvis styrkt av källor';
        } else if (validation.verdict === 'NOT_ENOUGH_INFO') {
            cls = 'info';
            label = 'Källorna räcker inte för att verifiera';
        } else if (validation.refused === true) {
            cls = 'info';
            label = 'Inget svar i källorna';
        } else if (validation.verdict === 'UNSUPPORTED' || validation.supported === false) {
            cls = 'warn';
            label = 'Ej styrkt av källor';
        }
        const badge = document.createElement('span');
        badge.className = 'badge ' + cls;
        badge.textContent = label;
        container.appendChild(badge);
        if (validation.supported === false && validation.reason) {
            const reason = document.createElement('div');
            reason.className = 'validation-reason';
            reason.textContent = 'Varför: ' + validation.reason;
            container.appendChild(reason);
        }
        if (Array.isArray(validation.unsupportedClaims) && validation.unsupportedClaims.length) {
            const claims = document.createElement('ul');
            claims.className = 'validation-claims';
            for (const claim of validation.unsupportedClaims) {
                const item = document.createElement('li');
                item.textContent = claim;
                claims.appendChild(item);
            }
            container.appendChild(claims);
        }
    }

    function sendQuestion(text) {
        questionInput.value = text;
        if (typeof chatForm.requestSubmit === 'function') {
            chatForm.requestSubmit();
        } else {
            chatForm.dispatchEvent(new Event('submit', {cancelable: true}));
        }
    }

    function appendFollowUps(container, questions) {
        if (!questions || !questions.length) return;
        const wrap = document.createElement('div');
        wrap.className = 'followups';
        const label = document.createElement('div');
        label.className = 'followups-label';
        label.textContent = '💡 Relaterade frågor';
        wrap.appendChild(label);
        const list = document.createElement('div');
        list.className = 'followup-list';
        for (const q of questions) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'followup-btn';
            btn.textContent = q;
            btn.addEventListener('click', () => sendQuestion(q));
            list.appendChild(btn);
        }
        wrap.appendChild(list);
        container.appendChild(wrap);
    }

    function parseEvent(raw) {
        let event = 'message';
        const dataLines = [];
        for (const line of raw.split('\n')) {
            if (line.startsWith('event:')) event = line.slice(6).trim();
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
        }
        if (!dataLines.length) return null;
        return {event, data: dataLines.join('\n')};
    }

    function createProgress(container) {
        const bar = document.createElement('div');
        bar.className = 'process';
        let steps = [];
        function makeSteps(labels) {
            bar.innerHTML = '';
            steps = labels.map(label => {
                const step = document.createElement('div');
                step.className = 'process-step';
                const icon = document.createElement('span');
                icon.className = 'process-icon';
                const text = document.createElement('span');
                text.className = 'process-label';
                text.textContent = label;
                step.appendChild(icon);
                step.appendChild(text);
                bar.appendChild(step);
                return step;
            });
        }
        function paint(active) {
            steps.forEach((step, i) => {
                step.classList.remove('done', 'active', 'wait');
                if (active === 'all' || i < active) step.classList.add('done');
                else if (i === active) step.classList.add('active');
                else step.classList.add('wait');
            });
        }
        makeSteps([
            'Sökning i källor',
            'Modell tänker & genererar',
            'Domaren analyserar',
            'Verifierat svar'
        ]);
        container.appendChild(bar);
        paint(0);
        return {
            el: bar,
            to(index) { paint(index); },
            finish() { paint('all'); },
            setDirect() { bar.remove(); }
        };
    }

    function buildAssistantLayout() {
        const div = document.createElement('div');
        div.className = 'msg assistant';

        const progress = createProgress(div);

        const thoughtsBox = document.createElement('details');
        thoughtsBox.className = 'thoughts';
        const summary = document.createElement('summary');
        summary.textContent = 'Steg 2: Modellens tankar';
        const thoughts = document.createElement('div');
        thoughts.className = 'thought-stream';
        thoughtsBox.appendChild(summary);
        thoughtsBox.appendChild(thoughts);
        div.appendChild(thoughtsBox);

        const answer = document.createElement('div');
        answer.className = 'answer draft';
        div.appendChild(answer);

        const judge = document.createElement('div');
        judge.className = 'judge-section waiting';
        const judgeLabel = document.createElement('div');
        judgeLabel.className = 'section-label';
        judgeLabel.textContent = 'Steg 3: Domarens analys';
        const judgeStatus = document.createElement('div');
        judgeStatus.className = 'section-status';
        judgeStatus.textContent = 'Väntar på verifiering...';
        judge.appendChild(judgeLabel);
        judge.appendChild(judgeStatus);
        div.appendChild(judge);

        return {div, progress, thoughtsBox, thoughts, answer, judge, judgeStatus};
    }

    function completeJudge(ui, validation) {
        ui.judge.classList.remove('waiting', 'active');
        ui.judge.classList.add('done');
        if (ui.judgeStatus) ui.judgeStatus.remove();
        appendValidation(ui.judge, validation);
    }

    function showFinalAnswer(ui) {
        ui.thoughtsBox.classList.remove('generating');
        ui.answer.classList.remove('draft');
    }

    function createStreamingAnswer() {
        const ui = buildAssistantLayout();
        ui.thoughtsBox.open = true;
        messages.appendChild(ui.div);
        messages.scrollTop = messages.scrollHeight;
        return ui;
    }

    newThreadBtn.addEventListener('click', () => createThread());

    function setSettingsStatus(text, cls) {
        settingsStatus.textContent = text;
        settingsStatus.className = 'status' + (cls ? ' ' + cls : '');
    }

    let ollamaModels = null;

    async function loadOllamaModels() {
        if (ollamaModels) return ollamaModels;
        try {
            const res = await fetch(API + '/models');
            const data = await res.json().catch(() => null);
            ollamaModels = Array.isArray(data) ? data : [];
        } catch (error) {
            ollamaModels = [];
        }
        return ollamaModels;
    }

    let qdrantCollections = null;

    async function loadCollections(force) {
        if (qdrantCollections && !force) return qdrantCollections;
        try {
            const res = await fetch(DOC_API + '/collections');
            const data = await res.json().catch(() => null);
            qdrantCollections = Array.isArray(data) ? data : [];
        } catch (error) {
            qdrantCollections = [];
        }
        return qdrantCollections;
    }

    let knownTags = [];

    async function loadTags() {
        try {
            const res = await fetch(DOC_API + '/tags');
            const data = await res.json().catch(() => null);
            knownTags = Array.isArray(data) ? data : [];
        } catch (error) {
            knownTags = [];
        }
        return knownTags;
    }

    function fillModelSelect(select, models, current) {
        if (!select) return;
        const names = new Set(models || []);
        if (current) names.add(current);
        select.innerHTML = '';
        for (const name of names) {
            const option = document.createElement('option');
            option.value = name;
            option.textContent = name;
            select.appendChild(option);
        }
        if (current) select.value = current;
    }

    function renderCollections(available, selected) {
        const chosen = new Set(Array.isArray(selected) ? selected : []);
        const names = new Set(available || []);
        chosen.forEach(name => names.add(name));
        collectionList.innerHTML = '';
        if (!names.size) {
            const empty = document.createElement('div');
            empty.className = 'collection-item';
            const text = document.createElement('span');
            text.className = 'name';
            text.textContent = 'Inga kollektioner hittades';
            empty.appendChild(text);
            collectionList.appendChild(empty);
            return;
        }
        for (const name of names) {
            const label = document.createElement('label');
            label.className = 'collection-item';
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.value = name;
            checkbox.checked = chosen.has(name);
            const text = document.createElement('span');
            text.className = 'name';
            text.textContent = name;
            label.appendChild(checkbox);
            label.appendChild(text);
            collectionList.appendChild(label);
        }
    }

    async function openSettings() {
        setSettingsStatus('Laddar...', '');
        settingsOverlay.classList.remove('hidden');
        try {
            const [res, models] = await Promise.all([fetch(API + '/settings'), loadOllamaModels()]);
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            for (const [key, value] of Object.entries(data)) {
                const field = settingsForm.elements.namedItem(key);
                if (!field || value === null || value === undefined) continue;
                if (field.type === 'checkbox') field.checked = value === true;
                else field.value = value;
            }
            fillModelSelect(settingsForm.elements.namedItem('chatModel'), models, data.chatModel);
            fillModelSelect(settingsForm.elements.namedItem('validatorModel'), models, data.validatorModel);
            fillModelSelect(settingsForm.elements.namedItem('embeddingModel'), models, data.embeddingModel);
            fillModelSelect(settingsForm.elements.namedItem('refineModel'), models, data.refineModel);
            fillModelSelect(settingsForm.elements.namedItem('gateModel'), models, data.gateModel);
            fillModelSelect(settingsForm.elements.namedItem('rewriteModel'), models, data.rewriteModel);
            if (!models.length) setSettingsStatus('Kunde inte hämta modeller från Ollama', 'err');
            else setSettingsStatus('', '');
        } catch (error) {
            setSettingsStatus('Kunde inte hämta: ' + error.message, 'err');
        }
    }

    function closeSettings() {
        settingsOverlay.classList.add('hidden');
    }

    settingsBtn.addEventListener('click', openSettings);
    settingsClose.addEventListener('click', closeSettings);
    docModalClose.addEventListener('click', closeDocumentPopup);
    docOverlay.addEventListener('click', (event) => {
        if (event.target === docOverlay) closeDocumentPopup();
    });
    document.addEventListener('click', (event) => {
        const link = event.target.closest('a.citation');
        if (!link) return;
        const message = link.closest('.msg');
        const number = link.dataset.sourceNumber;
        const target = message && number
            ? message.querySelector('.source-item[data-source-number="' + number + '"]')
            : null;
        if (!target) return;
        event.preventDefault();
        let node = target;
        while (node && node !== message) {
            if (node.tagName === 'DETAILS') node.open = true;
            node = node.parentElement;
        }
        target.scrollIntoView({block: 'nearest', behavior: 'smooth'});
    });

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !docOverlay.classList.contains('hidden')) closeDocumentPopup();
    });
    settingsOverlay.addEventListener('click', (event) => {
        if (event.target === settingsOverlay) closeSettings();
    });
    document.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;
        if (!settingsOverlay.classList.contains('hidden')) closeSettings();
        else if (!documentsPage.classList.contains('hidden')) closeDocuments();
        else if (!threadDocOverlay.classList.contains('hidden')) closeThreadDocSelector();
    });

    threadDocClose.addEventListener('click', closeThreadDocSelector);
    threadDocOverlay.addEventListener('click', (event) => {
        if (event.target === threadDocOverlay) closeThreadDocSelector();
    });
    threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => switchThreadDocTab(btn.dataset.tab));
    });
    threadDocSearch.addEventListener('input', renderThreadDocList);
    threadDocFilterCollection.addEventListener('change', renderThreadDocList);
    threadDocSave.addEventListener('click', saveThreadDocSelection);

    settingsForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        settingsSave.disabled = true;
        setSettingsStatus('Sparar...', '');

        const text = (name) => {
            const value = settingsForm.elements.namedItem(name).value.trim();
            return value === '' ? null : value;
        };
        const number = (name) => {
            const value = settingsForm.elements.namedItem(name).value.trim();
            return value === '' ? null : Number(value);
        };
        const checked = (name) => settingsForm.elements.namedItem(name).checked;

        const payload = {
            chatMode: text('chatMode'),
            chatModel: text('chatModel'),
            validatorModel: text('validatorModel'),
            validateGrounding: checked('validateGrounding'),
            smartSearch: checked('smartSearch'),
            rewriteQuery: checked('rewriteQuery'),
            similarityThreshold: number('similarityThreshold'),
            topN: number('topN'),
            temperature: number('temperature'),
            historySize: number('historySize'),
            embeddingModel: text('embeddingModel'),
            refineModel: text('refineModel'),
            gateModel: text('gateModel'),
            rewriteModel: text('rewriteModel')
        };

        try {
            const res = await fetch(API + '/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(payload)
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            setSettingsStatus('Sparat', 'ok');
        } catch (error) {
            setSettingsStatus('Fel: ' + error.message, 'err');
        } finally {
            settingsSave.disabled = false;
        }
    });

    function setDocumentsStatus(text, cls) {
        documentsStatus.textContent = text;
        documentsStatus.className = 'status' + (cls ? ' ' + cls : '');
    }

    let allDocuments = [];

    async function loadDocuments() {
        setDocumentsStatus('Laddar...', '');
        try {
            const res = await fetch(DOC_API);
            const data = await res.json().catch(() => []);
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            allDocuments = Array.isArray(data) ? data : [];
            const valid = new Set(allDocuments.map(d=>d.id));
            for (const id of Array.from(selectedDocIds)) if (!valid.has(id)) selectedDocIds.delete(id);
            persistSelectedDocIds();
            refreshDocFilters();
            const sc = localStorage.getItem('docs_filter_collection');
            if (sc && Array.from(docsFilterCollection.options).some(o=>o.value===sc)) docsFilterCollection.value=sc;
            const st = localStorage.getItem('docs_filter_tag');
            if (st && Array.from(docsFilterTag.options).some(o=>o.value===st)) docsFilterTag.value=st;
            const ss = localStorage.getItem('docs_sort');
            if (ss && Array.from(docsSort.options).some(o=>o.value===ss)) docsSort.value=ss;
            renderDocuments();
        } catch (error) {
            setDocumentsStatus('Kunde inte hämta: ' + error.message, 'err');
        }
    }

    function refreshDocFilters() {
        const collections = new Set();
        const tags = new Set();
        for (const doc of allDocuments) {
            if (doc.collection) collections.add(doc.collection);
            for (const tag of (doc.tags || [])) tags.add(tag);
        }
        fillFilterSelect(docsFilterCollection, 'Alla kollektioner', collections);
        fillFilterSelect(docsFilterTag, 'Alla taggar', tags);
    }

    function fillFilterSelect(select, allLabel, values) {
        const current = select.value;
        select.innerHTML = '';
        const all = document.createElement('option');
        all.value = '';
        all.textContent = allLabel;
        select.appendChild(all);
        for (const value of Array.from(values).sort((a, b) => a.localeCompare(b, 'sv'))) {
            const option = document.createElement('option');
            option.value = value;
            option.textContent = value;
            select.appendChild(option);
        }
        select.value = current && values.has(current) ? current : '';
    }

    function visibleDocuments() {
        const term = docsSearch.value.trim().toLowerCase();
        const collection = docsFilterCollection.value;
        const tag = docsFilterTag.value;
        const documents = allDocuments.filter(doc => {
            if (term && !(doc.name || '').toLowerCase().includes(term)) return false;
            if (collection && doc.collection !== collection) return false;
            if (tag && !(doc.tags || []).includes(tag)) return false;
            return true;
        });
        const byDate = (doc) => (doc.createdAt ? Date.parse(doc.createdAt) : 0);
        const byName = (doc) => (doc.name || '').toLowerCase();
        switch (docsSort.value) {
            case 'name-asc':
                documents.sort((a, b) => byName(a).localeCompare(byName(b), 'sv'));
                break;
            case 'name-desc':
                documents.sort((a, b) => byName(b).localeCompare(byName(a), 'sv'));
                break;
            case 'chunks-desc':
                documents.sort((a, b) => (b.chunks || 0) - (a.chunks || 0));
                break;
            case 'date-asc':
                documents.sort((a, b) => byDate(a) - byDate(b));
                break;
            default:
                documents.sort((a, b) => byDate(b) - byDate(a));
        }
        return documents;
    }

    function renderDocuments() {
        const documents = visibleDocuments();
        docList.innerHTML = '';
        if (!documents.length) {
            const empty = document.createElement('div');
            empty.className = 'doc-item';
            empty.innerHTML = '<span>Inga dokument att visa.</span>';
            docList.appendChild(empty);
        } else {
            for (const doc of documents) {
                docList.appendChild(documentRow(doc));
            }
        }
        const total = allDocuments.length;
        updateBulkBar();
        setDocumentsStatus(documents.length === total
            ? total + ' dokument'
            : documents.length + ' av ' + total + ' dokument', '');
    }

    function documentRow(doc) {
        const row = document.createElement('div');
        row.className = 'doc-item';

        const check = document.createElement('input');
        check.type = 'checkbox';
        check.className = 'doc-check';
        check.checked = selectedDocIds.has(doc.id);
        if (check.checked) row.classList.add('selected');
        check.addEventListener('change', () => {
            if (check.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
            persistSelectedDocIds();
            row.classList.toggle('selected', check.checked);
            updateBulkBar();
        });

        const main = document.createElement('div');
        main.className = 'doc-main';
        const nameEl = document.createElement('div');
        nameEl.className = 'doc-name';
        nameEl.textContent = doc.name;
        nameEl.title = doc.name;
        const metaEl = document.createElement('div');
        metaEl.className = 'meta';
        let meta = doc.chunks + ' textstycken';
        if (doc.collection) meta += ' · ' + doc.collection;
        if (doc.createdAt) meta += ' · ' + formatDate(doc.createdAt);
        metaEl.textContent = meta;
        main.appendChild(nameEl);
        main.appendChild(metaEl);
        if (doc.tags && doc.tags.length) {
            const tagList = document.createElement('div');
            tagList.className = 'tag-list';
            for (const tag of doc.tags) {
                const chip = document.createElement('span');
                chip.className = 'tag';
                chip.textContent = tag;
                tagList.appendChild(chip);
            }
            main.appendChild(tagList);
        }

        const actions = document.createElement('div');
        actions.className = 'doc-actions';

        const view = document.createElement('button');
        view.type = 'button';
        view.textContent = 'Visa';
        view.addEventListener('click', () => viewDocument(doc.id));

        const download = document.createElement('a');
        download.href = DOC_API + '/' + encodeURIComponent(doc.id) + '/download';
        download.textContent = 'Ladda ner';

        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'danger';
        del.textContent = 'Ta bort';
        del.addEventListener('click', () => deleteDocument(doc.id));

        actions.appendChild(view);
        actions.appendChild(download);
        actions.appendChild(del);

        row.appendChild(check);
        row.appendChild(main);
        row.appendChild(actions);
        return row;
    }

    function formatDate(value) {
        const date = new Date(value);
        if (isNaN(date.getTime())) return '';
        return date.toLocaleDateString('sv-SE') + ' ' +
            date.toLocaleTimeString('sv-SE', {hour: '2-digit', minute: '2-digit'});
    }

    function docPdfUrl(ref) {
        if (!ref.documentId) return null;
        const url = DOC_API + '/' + encodeURIComponent(ref.documentId) + '/pdf';
        return ref.page !== undefined && ref.page !== null ? url + '#page=' + encodeURIComponent(ref.page) : url;
    }

    function docDownloadUrl(ref) {
        return ref.documentId ? DOC_API + '/' + encodeURIComponent(ref.documentId) + '/download' : null;
    }

    function documentMeta(doc) {
        let meta = (doc.chunks || 0) + ' textstycken \u00b7 ' + (doc.characters || 0) + ' tecken';
        if (doc.collection) meta += ' \u00b7 ' + doc.collection;
        if (doc.tags && doc.tags.length) meta += ' \u00b7 ' + doc.tags.join(', ');
        return meta;
    }

    function setDocModalMeta(text) {
        docModalMeta.textContent = text || '';
        docModalMeta.classList.toggle('hidden', !text);
    }

    function showDocModalText(text) {
        docModalText.innerHTML = '';
        if (Array.isArray(text)) {
            if (!text.length) docModalText.textContent = 'Inget textinneh\u00e5ll.';
            for (const chunk of text) {
                const block = document.createElement('div');
                block.className = 'doc-chunk';
                if (chunk.page !== null && chunk.page !== undefined) {
                    const page = document.createElement('div');
                    page.className = 'doc-chunk-page';
                    page.textContent = 'Sida ' + chunk.page;
                    block.appendChild(page);
                }
                const body = document.createElement('div');
                body.textContent = chunk.text || '';
                block.appendChild(body);
                docModalText.appendChild(block);
            }
        } else {
            docModalText.textContent = text || 'Inget textinneh\u00e5ll.';
        }
        docModalText.classList.remove('hidden');
        docModalFrame.classList.add('hidden');
    }

    function showDocModalPdf(url, text) {
        docModalText.classList.add('hidden');
        docModalText.innerHTML = '';
        docModalFrame.src = url;
        docModalFrame.classList.remove('hidden');
        if (!text) return;
        const toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.textContent = 'Visa text';
        toggle.addEventListener('click', () => {
            const showText = docModalText.classList.contains('hidden');
            if (showText) showDocModalText(text);
            else {
                docModalText.classList.add('hidden');
                docModalFrame.classList.remove('hidden');
            }
            toggle.textContent = showText ? 'Visa PDF' : 'Visa text';
        });
        docModalActions.insertBefore(toggle, docModalActions.firstChild);
    }

    async function openDocumentPopup(ref) {
        docOverlay.classList.remove('hidden');
        docModalTitle.textContent = ref.title || 'Dokument';
        setDocModalMeta(ref.meta);
        docModalActions.innerHTML = '';
        docModalFrame.removeAttribute('src');
        docModalFrame.classList.add('hidden');
        docModalText.classList.add('hidden');
        docModalText.innerHTML = '';

        if (ref.documentId && !ref.text) {
            setDocModalMeta('Laddar...');
            try {
                const res = await fetch(DOC_API + '/' + encodeURIComponent(ref.documentId));
                const doc = await res.json().catch(() => ({}));
                if (!res.ok) throw new Error(doc.error || ('HTTP ' + res.status));
                ref = Object.assign({}, ref, {
                    title: ref.title || doc.name,
                    meta: ref.meta || documentMeta(doc),
                    text: ref.text || doc.content || null
                });
            } catch (error) {
                setDocModalMeta('Kunde inte h\u00e4mta: ' + error.message);
            }
        }
        if (ref.title) docModalTitle.textContent = ref.title;
        setDocModalMeta(ref.meta);

        const download = docDownloadUrl(ref);
        if (download) {
            const link = document.createElement('a');
            link.href = download;
            link.textContent = 'Ladda ner';
            docModalActions.appendChild(link);
        }

        const pdf = docPdfUrl(ref);
        if (pdf) showDocModalPdf(pdf, ref.text);
        else showDocModalText(ref.text);
    }

    function closeDocumentPopup() {
        docOverlay.classList.add('hidden');
        docModalFrame.removeAttribute('src');
        docModalFrame.classList.add('hidden');
        docModalText.classList.add('hidden');
        docModalText.innerHTML = '';
        docModalActions.innerHTML = '';
    }

    async function viewDocument(id) {
        await openDocumentPopup({documentId: id});
    }

    async function deleteDocument(id) {
        if (!confirm('Ta bort dokumentet och alla dess vektorer från arkivet?')) return;
        try {
            const res = await fetch(DOC_API + '/' + encodeURIComponent(id), {method: 'DELETE'});
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            await loadDocuments();
        } catch (error) {
            setDocumentsStatus('Fel: ' + error.message, 'err');
        }
    }

    function updateBulkBar() {
        const n = selectedDocIds.size;
        docsBulkCount.textContent = n ? n + ' markerade' : '';
        docsBulkDownload.disabled = !n;
        docsBulkDelete.disabled = !n;
        const visible = visibleDocuments();
        docsSelectAll.checked = visible.length > 0 && visible.every(d => selectedDocIds.has(d.id));
        docsSelectAll.indeterminate = visible.some(d => selectedDocIds.has(d.id)) && !docsSelectAll.checked;
    }

    docsSelectAll.addEventListener('change', () => {
        for (const doc of visibleDocuments()) {
            if (docsSelectAll.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
            persistSelectedDocIds();
        }
        renderDocuments();
    });

    docsBulkDownload.addEventListener('click', () => {
        for (const id of Array.from(selectedDocIds)) {
            const a = document.createElement('a');
            a.href = DOC_API + '/' + encodeURIComponent(id) + '/download';
            a.download = '';
            document.body.appendChild(a);
            a.click();
            a.remove();
        }
    });

    docsBulkDelete.addEventListener('click', async () => {
        if (!selectedDocIds.size) return;
        if (!confirm('Ta bort ' + selectedDocIds.size + ' markerade dokument och alla deras vektorer?')) return;
        const ids = Array.from(selectedDocIds);
        let failed = 0;
        for (const id of ids) {
            try {
                const res = await fetch(DOC_API + '/' + encodeURIComponent(id), {method: 'DELETE'});
                if (!res.ok) throw new Error('HTTP ' + res.status);
                selectedDocIds.delete(id);
                persistSelectedDocIds();
            } catch (e) {
                failed++;
            }
        }
        await loadDocuments();
        setDocumentsStatus((ids.length - failed) + ' dokument borttagna' + (failed ? ' · ' + failed + ' misslyckades' : ''), failed ? 'err' : 'ok');
    });

    function openDocuments() {
        closeDocumentPopup();
        documentsPage.classList.remove('hidden');
        loadDocuments();
        loadUploadOptions();
    }

    function closeDocuments() {
        closeDocumentPopup();
        documentsPage.classList.add('hidden');
    }

    function openThreadDocSelector(threadId) {
        threadDocTargetId = threadId;
        threadDocOverlay.classList.remove('hidden');
        threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === 'collections');
        });
        threadDocOverlay.querySelectorAll('.tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.dataset.panel === 'collections');
        });
        threadDocSearch.value = '';
        threadDocFilterCollection.value = '';
        loadThreadDocCollections();
        loadThreadDocDocuments();
    }

    function closeThreadDocSelector() {
        threadDocOverlay.classList.add('hidden');
        threadDocTargetId = null;
        threadDocCollectionList.innerHTML = '';
        threadDocList.innerHTML = '';
        threadDocStatus.textContent = '';
        threadDocStatus.className = 'status';
    }

    function switchThreadDocTab(name) {
        threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === name);
        });
        threadDocOverlay.querySelectorAll('.tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.dataset.panel === name);
        });
    }

    async function loadThreadDocCollections() {
        try {
            const res = await fetch(DOC_API + '/collections');
            const collections = await res.json().catch(() => []);
            const thread = threads.find(t => t.id === threadDocTargetId);
            const selected = new Set(thread?.collections || []);
            threadDocCollectionList.innerHTML = '';
            if (!collections.length) {
                const empty = document.createElement('div');
                empty.className = 'collection-item';
                const text = document.createElement('span');
                text.className = 'name';
                text.textContent = 'Inga kollektioner hittades';
                empty.appendChild(text);
                threadDocCollectionList.appendChild(empty);
                return;
            }
            for (const name of collections) {
                const label = document.createElement('label');
                label.className = 'collection-item';
                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.value = name;
                checkbox.checked = selected.has(name);
                const text = document.createElement('span');
                text.className = 'name';
                text.textContent = name;
                label.appendChild(checkbox);
                label.appendChild(text);
                threadDocCollectionList.appendChild(label);
            }
        } catch (error) {
            threadDocStatus.textContent = 'Kunde inte hämta kollektioner: ' + error.message;
            threadDocStatus.className = 'status err';
        }
    }

    async function loadThreadDocDocuments() {
        try {
            const res = await fetch(DOC_API);
            const documents = await res.json().catch(() => []);
            allDocuments = Array.isArray(documents) ? documents : [];
            const thread = threads.find(t => t.id === threadDocTargetId);
            const selectedDocIds = new Set(thread?.documentIds || []);
            const collections = new Set();
            for (const doc of allDocuments) {
                if (doc.collection) collections.add(doc.collection);
            }
            threadDocFilterCollection.innerHTML = '<option value="">Alla kollektioner</option>';
            for (const name of Array.from(collections).sort((a, b) => a.localeCompare(b, 'sv'))) {
                const option = document.createElement('option');
                option.value = name;
                option.textContent = name;
                threadDocFilterCollection.appendChild(option);
            }
            renderThreadDocList();
        } catch (error) {
            threadDocStatus.textContent = 'Kunde inte hämta dokument: ' + error.message;
            threadDocStatus.className = 'status err';
        }
    }

    function renderThreadDocList() {
        const term = threadDocSearch.value.trim().toLowerCase();
        const collection = threadDocFilterCollection.value;
        const thread = threads.find(t => t.id === threadDocTargetId);
        const selectedDocIds = new Set(thread?.documentIds || []);
        const documents = allDocuments.filter(doc => {
            if (term && !(doc.name || '').toLowerCase().includes(term)) return false;
            if (collection && doc.collection !== collection) return false;
            return true;
        });
        threadDocList.innerHTML = '';
        if (!documents.length) {
            const empty = document.createElement('div');
            empty.className = 'doc-item';
            empty.innerHTML = '<span>Inga dokument att visa.</span>';
            threadDocList.appendChild(empty);
        } else {
            for (const doc of documents) {
                threadDocList.appendChild(threadDocumentRow(doc, selectedDocIds));
            }
        }
    }

    function threadDocumentRow(doc, selectedDocIds) {
        const row = document.createElement('div');
        row.className = 'doc-item';

        const check = document.createElement('input');
        check.type = 'checkbox';
        check.className = 'doc-check';
        check.checked = selectedDocIds.has(doc.id);
        if (check.checked) row.classList.add('selected');
        check.addEventListener('change', () => {
            if (check.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
            row.classList.toggle('selected', check.checked);
        });

        const main = document.createElement('div');
        main.className = 'doc-main';
        const nameEl = document.createElement('div');
        nameEl.className = 'doc-name';
        nameEl.textContent = doc.name;
        nameEl.title = doc.name;
        const metaEl = document.createElement('div');
        metaEl.className = 'meta';
        let meta = doc.chunks + ' textstycken';
        if (doc.collection) meta += ' · ' + doc.collection;
        if (doc.createdAt) meta += ' · ' + formatDate(doc.createdAt);
        metaEl.textContent = meta;
        main.appendChild(nameEl);
        main.appendChild(metaEl);
        if (doc.tags && doc.tags.length) {
            const tagList = document.createElement('div');
            tagList.className = 'tag-list';
            for (const tag of doc.tags) {
                const chip = document.createElement('span');
                chip.className = 'tag';
                chip.textContent = tag;
                tagList.appendChild(chip);
            }
            main.appendChild(tagList);
        }

        row.appendChild(check);
        row.appendChild(main);
        return row;
    }

    function saveThreadDocSelection() {
        const thread = threads.find(t => t.id === threadDocTargetId);
        if (!thread) return;
        const collections = Array.from(
            threadDocCollectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
        const documentIds = Array.from(
            threadDocList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
        thread.collections = collections;
        thread.documentIds = documentIds;
        saveThreads();
        renderThreadList();
        threadDocStatus.textContent = 'Sparat';
        threadDocStatus.className = 'status ok';
        setTimeout(() => {
            threadDocStatus.textContent = '';
            threadDocStatus.className = 'status';
        }, 2000);
    }

    function switchDocumentsTab(name) {
        documentsPage.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === name);
        });
        documentsPage.querySelectorAll('.tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.dataset.panel === name);
        });
        if (name === 'collections') loadCollectionsTab();
        if (name === 'upload') loadUploadOptions();
    }

    async function loadUploadOptions() {
        const [collections] = await Promise.all([loadCollections(true), loadTags()]);
        const current = uploadCollection.value;
        uploadCollection.innerHTML = '';
        for (const name of collections) {
            const option = document.createElement('option');
            option.value = name;
            option.textContent = name;
            uploadCollection.appendChild(option);
        }
        const create = document.createElement('option');
        create.value = '__new__';
        create.textContent = '＋ Ny kollektion…';
        uploadCollection.appendChild(create);
        if (current && Array.from(uploadCollection.options).some(o => o.value === current)) {
            uploadCollection.value = current;
        }
        toggleNewCollectionField();
        renderTagSuggestions();
    }

    function toggleNewCollectionField() {
        newCollectionField.classList.toggle('hidden', uploadCollection.value !== '__new__');
    }

    function renderTagSuggestions() {
        tagSuggestions.innerHTML = '';
        if (!knownTags.length) return;
        const label = document.createElement('span');
        label.className = 'meta';
        label.textContent = 'Befintliga taggar:';
        tagSuggestions.appendChild(label);
        for (const tag of knownTags) {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = tag;
            button.addEventListener('click', () => addTag(uploadTags, tag));
            tagSuggestions.appendChild(button);
        }
    }

    function addTag(input, tag) {
        const tags = input.value.split(',').map(t => t.trim()).filter(Boolean);
        if (!tags.includes(tag)) {
            tags.push(tag);
        }
        input.value = tags.join(', ');
    }

    async function loadCollectionsTab() {
        setCollectionsStatus('Laddar...', '');
        try {
            const [collections, settingsRes] = await Promise.all([loadCollections(), fetch(API + '/settings')]);
            const settings = await settingsRes.json().catch(() => ({}));
            renderCollections(collections, settings.collections);
            setCollectionsStatus('', '');
        } catch (error) {
            setCollectionsStatus('Kunde inte hämta: ' + error.message, 'err');
        }
    }

    function setCollectionsStatus(text, cls) {
        collectionsStatus.textContent = text;
        collectionsStatus.className = 'status' + (cls ? ' ' + cls : '');
    }

    collectionsSave.addEventListener('click', async () => {
        collectionsSave.disabled = true;
        setCollectionsStatus('Sparar...', '');
        const collections = Array.from(
            collectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
        try {
            const res = await fetch(API + '/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({collections: collections})
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            setCollectionsStatus('Sparat', 'ok');
        } catch (error) {
            setCollectionsStatus('Fel: ' + error.message, 'err');
        } finally {
            collectionsSave.disabled = false;
        }
    });

    documentsBtn.addEventListener('click', openDocuments);
    documentsClose.addEventListener('click', closeDocuments);
    docsSearch.addEventListener('input', renderDocuments);
    docsFilterCollection.addEventListener('change', () => { localStorage.setItem('docs_filter_collection', docsFilterCollection.value); renderDocuments(); });
    docsFilterTag.addEventListener('change', () => { localStorage.setItem('docs_filter_tag', docsFilterTag.value); renderDocuments(); });
    docsSort.addEventListener('change', () => { localStorage.setItem('docs_sort', docsSort.value); renderDocuments(); });
    documentsPage.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => switchDocumentsTab(btn.dataset.tab));
    });
    uploadCollection.addEventListener('change', () => {
        if (uploadCollection.value !== '__new__') {
            newCollectionName.value = '';
        }
        toggleNewCollectionField();
    });

    createCollectionBtn.addEventListener('click', async () => {
        const name = newCollectionInput.value.trim();
        if (!name) return;
        createCollectionBtn.disabled = true;
        setCollectionsStatus('Skapar...', '');
        try {
            const res = await fetch(DOC_API + '/collections', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name})
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            newCollectionInput.value = '';
            qdrantCollections = Array.isArray(data) ? data : null;
            setCollectionsStatus('Skapad', 'ok');
            renderCollections(await loadCollections(true), getSelectedCollections());
        } catch (error) {
            setCollectionsStatus('Fel: ' + error.message, 'err');
        } finally {
            createCollectionBtn.disabled = false;
        }
    });

    function getSelectedCollections() {
        return Array.from(collectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
    }

    function formatBytes(bytes) {
        if (!bytes) return '0 B';
        const units = ['B', 'kB', 'MB', 'GB'];
        let value = bytes;
        let i = 0;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return (i === 0 ? Math.round(value) : value.toFixed(1)) + ' ' + units[i];
    }

    function setUploadProgress(state, label, pct, detail) {
        uploadProgress.className = 'upload-progress' + (state ? ' ' + state : '');
        if (label !== undefined) uploadProgressLabel.textContent = label;
        if (pct !== undefined) {
            const clamped = pct === null ? 0 : Math.max(0, Math.min(100, pct));
            uploadProgressFill.style.width = clamped + '%';
            uploadProgressPct.textContent = pct === null ? '' : Math.round(pct) + '%';
        }
        if (detail !== undefined) uploadProgressDetail.textContent = detail;
    }

    function hideUploadProgress() {
        uploadProgress.className = 'upload-progress hidden';
        setUploadProgress('', '', 0, '');
    }

    function uploadFile(file, collection, tags, onProgress) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            xhr.open('POST', DOC_API);
            xhr.upload.onprogress = (event) => {
                if (event.lengthComputable && onProgress) onProgress(event.loaded, event.total);
            };
            xhr.onload = () => {
                let data = {};
                try {
                    data = JSON.parse(xhr.responseText || '{}');
                } catch (ignored) {
                    data = {};
                }
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve(Array.isArray(data) ? data : []);
                } else {
                    reject(new Error(data.error || ('HTTP ' + xhr.status)));
                }
            };
            xhr.onerror = () => reject(new Error('Uppladdningen avbröts'));
            xhr.onabort = () => reject(new Error('Uppladdningen avbröts'));
            const formData = new FormData();
            formData.append('files', file);
            if (collection) formData.append('collection', collection);
            for (const tag of tags) formData.append('tags', tag);
            xhr.send(formData);
        });
    }

    let uploadInProgress = false;
    window.addEventListener('beforeunload', (event) => {
        if (uploadInProgress) {
            event.preventDefault();
            event.returnValue = 'Uppladdning pågår – vill du lämna sidan?';
        }
    });

    uploadForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        const files = Array.from(uploadForm.elements.namedItem('files').files);
        if (!files.length) {
            setDocumentsStatus('Välj minst en fil', 'err');
            return;
        }
        let collection = uploadCollection.value;
        if (collection === '__new__') {
            collection = newCollectionName.value.trim();
            if (!collection) {
                setDocumentsStatus('Ange namn på den nya kollektionen', 'err');
                return;
            }
        }
        const tags = uploadTags.value.split(',').map(t => t.trim()).filter(Boolean);
        const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
        uploadBtn.disabled = true;
        uploadInProgress = true;
        setDocumentsStatus('Laddar upp...', '');
        setUploadProgress('', 'Förbereder uppladdning', 0,
            files.length + ' fil(er) · ' + formatBytes(totalBytes));

        const uploaded = [];
        let sentBytes = 0;
        try {
            for (let i = 0; i < files.length; i++) {
                const file = files[i];
                const base = sentBytes;
                setUploadProgress('', 'Fil ' + (i + 1) + ' av ' + files.length + ' · ' + file.name,
                    totalBytes ? (base / totalBytes) * 100 : 100,
                    formatBytes(base) + ' av ' + formatBytes(totalBytes));
                const added = await uploadFile(file, collection, tags, (loaded) => {
                    const pct = totalBytes ? ((base + loaded) / totalBytes) * 100 : 100;
                    setUploadProgress('', 'Fil ' + (i + 1) + ' av ' + files.length + ' · ' + file.name,
                        pct, formatBytes(base + loaded) + ' av ' + formatBytes(totalBytes));
                });
                uploaded.push(...added);
                sentBytes += file.size;
            }

            setUploadProgress('processing', 'Alla filer skickade', 100,
                'Bearbetar, chunkar och indexerar i arkivet...');
            uploadForm.reset();
            newCollectionName.value = '';
            uploadTags.value = '';
            toggleNewCollectionField();
            qdrantCollections = null;
            await Promise.all([loadUploadOptions(), loadDocuments()]);
            const addedCount = uploaded.filter(d => !d.skipped).length;
            const skippedCount = uploaded.filter(d => d.skipped).length;
            let detail = addedCount + ' dokument tillagda i arkivet';
            const notice = uploaded.map(d => d.notice).filter(Boolean).join(' · ');
            if (notice) detail += ' · ' + notice;
            setUploadProgress('ok', 'Klart', 100, detail);
            let msg = addedCount + ' dokument uppladdade';
            if (skippedCount) msg += ' · ' + skippedCount + ' redan i arkivet';
            if (notice) msg += ' · ' + notice;
            setDocumentsStatus(msg, skippedCount && !addedCount ? '' : 'ok');
            window.setTimeout(hideUploadProgress, 5000);
        } catch (error) {
            setUploadProgress('err', 'Fel vid uppladdning', null, error.message);
            setDocumentsStatus('Fel: ' + error.message, 'err');
        } finally {
            uploadBtn.disabled = false;
            uploadInProgress = false;
        }
    });

    uploadFolderBtn.addEventListener('click', () => uploadFolderInput.click());
    uploadFolderInput.addEventListener('change', () => {
        const picked = Array.from(uploadFolderInput.files || []).filter(f => /\.pdf$/i.test(f.name));
        if (!picked.length) {
            setDocumentsStatus('Inga PDF-filer i vald mapp', 'err');
            uploadFolderInput.value = '';
            return;
        }
        const dt = new DataTransfer();
        for (const f of picked) dt.items.add(f);
        const input = uploadForm.elements.namedItem('files');
        input.files = dt.files;
        setDocumentsStatus(picked.length + ' PDF-filer valda från mapp – klicka "Ladda upp"', '');
        uploadFolderInput.value = '';
    });

    chatForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        const question = questionInput.value.trim();
        if (!question) return;
        if (!activeThread()) createThread();

        const thread = activeThread();
        thread.messages.push({role: 'user', text: question});
        saveThreads();
        renderThreadList();
        renderMessage({role: 'user', text: question});

        questionInput.value = '';
        sendBtn.disabled = true;

        const ui = createStreamingAnswer();
        const {div, answer} = ui;
        let contentText = '';
        let thoughtsText = '';
        let originalText = '';
        let answerHtml = '';
        let sources = [];
        let validation = null;
        let followUps = [];
        let wasRefined = false;
        let directMode = false;
        let searchQuery = '';

        try {
            const response = await fetch(API + '/stream', {
                method: 'POST',
                headers: {'Content-Type': 'application/json', 'Accept': 'text/event-stream'},
                body: JSON.stringify({
                    message: question,
                    sessionId: thread.id,
                    collections: thread.collections,
                    documentIds: thread.documentIds
                })
            });
            if (!response.ok || !response.body) {
                const error = await response.json().catch(() => ({}));
                div.remove();
                addMessage(error.error || ('Fel: ' + response.status), 'error');
                return;
            }

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let done = false;
            while (!done) {
                const result = await reader.read();
                if (result.done) break;
                buffer += decoder.decode(result.value, {stream: true});
                let sep;
                while ((sep = buffer.indexOf('\n\n')) !== -1) {
                    const raw = buffer.slice(0, sep);
                    buffer = buffer.slice(sep + 2);
                    const evt = parseEvent(raw);
                    if (!evt) continue;

                    if (evt.event === 'content') {
                        contentText += JSON.parse(evt.data);
                        answer.textContent = contentText;
                        messages.scrollTop = messages.scrollHeight;
                    } else if (evt.event === 'thought') {
                        thoughtsText += JSON.parse(evt.data);
                        ui.thoughts.textContent = thoughtsText;
                        messages.scrollTop = messages.scrollHeight;
                    } else if (evt.event === 'search') {
                        const info = JSON.parse(evt.data) || {};
                        searchQuery = info.query || '';
                        if (info.needed === false) {
                            directMode = true;
                            ui.progress.setDirect();
                            ui.thoughtsBox.classList.add('generating');
                            if (ui.judge) ui.judge.remove();
                        }
                    } else if (evt.event === 'final') {
                        contentText = JSON.parse(evt.data);
                        answer.textContent = contentText;
                        ui.thoughtsBox.classList.remove('generating');
                        if (!directMode) {
                            ui.progress.to(2);
                            ui.judge.classList.remove('waiting');
                            ui.judge.classList.add('active');
                            ui.judgeStatus.textContent = 'Analyserar svaret mot källorna...';
                        }
                        messages.scrollTop = messages.scrollHeight;
                    } else if (evt.event === 'refined') {
                        const refinedText = JSON.parse(evt.data);
                        if (contentText && contentText !== refinedText) originalText = contentText;
                        contentText = refinedText;
                        answer.textContent = contentText;
                        wasRefined = true;
                        messages.scrollTop = messages.scrollHeight;
                    } else if (evt.event === 'answerHtml') {
                        answerHtml = JSON.parse(evt.data) || '';
                        if (answerHtml) {
                            answer.classList.add('md');
                            answer.innerHTML = answerHtml;
                        }
                        messages.scrollTop = messages.scrollHeight;
                    } else if (evt.event === 'sources') {
                        sources = JSON.parse(evt.data);
                        ui.progress.to(1);
                        ui.thoughtsBox.classList.add('generating');
                    } else if (evt.event === 'validation') {
                        validation = JSON.parse(evt.data);
                        ui.progress.to(3);
                        completeJudge(ui, validation);
                    } else if (evt.event === 'followups') {
                        followUps = JSON.parse(evt.data) || [];
                        appendFollowUps(div, followUps);
                    } else if (evt.event === 'error') {
                        const payload = JSON.parse(evt.data) || {};
                        div.remove();
                        addMessage(payload.message || 'Kunde inte slutföra svaret.', 'error');
                        done = true;
                    } else if (evt.event === 'done') {
                        if (!contentText) {
                            answer.classList.remove('md');
                            answer.textContent = '(inget svar)';
                        }
                        showFinalAnswer(ui);
                        if (directMode) {
                            if (!thoughtsText && ui.thoughtsBox) ui.thoughtsBox.remove();
                        } else if (!ui.judge.classList.contains('done')) {
                            ui.judge.classList.remove('waiting', 'active');
                            ui.judge.classList.add('done');
                            if (ui.judgeStatus) ui.judgeStatus.textContent = 'Ingen verifiering';
                        }
                        ui.progress.finish();
                        if (wasRefined) appendRefinedNote(div);
                        appendSources(div, sources, searchQuery);
                        if (originalText) appendOriginalAnswer(div, originalText);
                        thread.messages.push({role: 'assistant', text: contentText || '(inget svar)', html: answerHtml || null, sources: sources, validation: validation, thoughts: thoughtsText || null, followUps: followUps.length ? followUps : null, refined: wasRefined, originalText: originalText || null, searched: !directMode, searchQuery: searchQuery || null});
                        saveThreads();
                        renderThreadList();
                        done = true;
                    }
                }
            }
        } catch (error) {
            if (!contentText) div.remove();
            addMessage('Nätverksfel: ' + error.message, 'error');
        } finally {
            sendBtn.disabled = false;
            questionInput.focus();
        }
    });

    if (!threads.length) {
        createThread();
    } else {
        renderThreadList();
        renderMessages();
    }
