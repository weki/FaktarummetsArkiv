/* Chattens DOM-referenser och tillstånd. */
const app = document.getElementById('app');
const messages = document.getElementById('messages');
const messagesInner = document.getElementById('messagesInner');
const chatForm = document.getElementById('chatForm');
const questionInput = document.getElementById('question');
const sendBtn = document.getElementById('sendBtn');
const newThreadBtn = document.getElementById('newThreadBtn');
const threadList = document.getElementById('threadList');
const threadSearch = document.getElementById('threadSearch');
const threadTitleEl = document.getElementById('threadTitle');
const threadScopeBtn = document.getElementById('threadScopeBtn');
const threadScopeLabel = document.getElementById('threadScopeLabel');
const exportBtn = document.getElementById('exportBtn');
const sourcesToggle = document.getElementById('sourcesToggle');
const sourcePanelClose = document.getElementById('sourcePanelClose');
const sourceCount = document.getElementById('sourceCount');
const sourceBody = document.getElementById('sourceBody');
const shortcutsDialog = document.getElementById('shortcutsDialog');

const PANEL_KEY = 'source_panel_open';

let streaming = false;
let liveDraft = null;
let streamAbort = null;
const panel = {
    messageIndex: null,
    sourceNumber: null,
    showUncited: false,
    open: localStorage.getItem(PANEL_KEY) !== 'false'
};
