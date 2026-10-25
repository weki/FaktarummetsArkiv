/* Trådar i localStorage (delas av chatt och sidopanel). */
const STORAGE_KEY = 'fakturummets_arkiv_rag_threads';

let threads = loadThreads();
let activeId = threads.length ? sortedThreads()[0].id : null;

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
    return firstUser ? firstUser.text : 'Ny tråd';
}

function sortedThreads() {
    return threads.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

function touchThread(thread) {
    thread.updatedAt = Date.now();
}

function scopeLabel(thread) {
    const docs = (thread && thread.documentIds) ? thread.documentIds.length : 0;
    const cols = (thread && thread.collections) ? thread.collections.length : 0;
    if (docs) return docs + (docs === 1 ? ' dokument valt' : ' dokument valda');
    if (cols) return cols + (cols === 1 ? ' kollektion' : ' kollektioner');
    return 'alla dokument';
}

function isToday(ts) {
    return ts && new Date(ts).toDateString() === new Date().toDateString();
}

function formatThreadTime(ts) {
    if (!ts) return '';
    const date = new Date(ts);
    if (isToday(ts)) return date.toLocaleTimeString('sv-SE', {hour: '2-digit', minute: '2-digit'});
    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    if (date.toDateString() === yesterday.toDateString()) return 'Igår';
    return date.toLocaleDateString('sv-SE', {day: 'numeric', month: 'short'}).replace(/\.$/, '');
}
