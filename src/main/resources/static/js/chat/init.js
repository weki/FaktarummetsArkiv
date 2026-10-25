/* Start av chatten. */
setPanelOpen(panel.open);
const startParams = new URLSearchParams(location.search);
const startThread = startParams.get('thread');
if (startThread && threads.some(t => t.id === startThread)) activeId = startThread;
if (!threads.length || startParams.get('new')) {
    createThread();
} else {
    renderAll();
}
if (startParams.has('thread') || startParams.has('new')) {
    history.replaceState(null, '', location.pathname);
}
