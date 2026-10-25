/* Modellstatus i sidopanelen (alla sidor). */
const modelStatus = document.getElementById('modelStatus');
const modelStatusText = document.getElementById('modelStatusText');
const resetOllamaBtn = document.getElementById('resetOllamaBtn');

// ---------- Model status ----------

async function loadModelStatus() {
    try {
        const [settingsRes, modelsRes] = await Promise.all([fetch(API + '/settings'), fetch(API + '/models')]);
        const settings = await settingsRes.json().catch(() => ({}));
        const models = modelsRes.ok ? await modelsRes.json().catch(() => []) : [];
        const chat = settings.chatModel || 'okänd modell';
        const embed = settings.embeddingModel ? settings.embeddingModel.split('/').pop() : null;
        const label = chat + (embed ? ' + ' + embed : '');
        const available = Array.isArray(models) && models.length;
        const missing = available && settings.chatModel && !models.includes(settings.chatModel);
        modelStatus.className = 'model-status ' + (!available ? 'err' : missing ? 'warn' : 'ok');
        modelStatusText.textContent = !available ? 'Ollama svarar inte' : missing ? label + ' · saknas' : label;
        modelStatus.title = label;
    } catch (error) {
        modelStatus.className = 'model-status err';
        modelStatusText.textContent = 'Kunde inte nå servern';
    }
}

// Chatten ersätter dessa (se chat/stream.js). På andra sidor finns inget pågående svar.
let isAnswering = () => false;
let abortAnswer = () => {};

async function resetOllama() {
    if (isAnswering() && !confirm('Ett svar genereras just nu. Avbryta det och återställa Ollama?')) return;
    resetOllamaBtn.disabled = true;
    modelStatus.className = 'model-status';
    modelStatusText.textContent = 'Återställer Ollama…';
    abortAnswer();
    try {
        const res = await fetch(API + '/ollama/reset', {
            method: 'POST',
            headers: {'X-Requested-With': 'FakturummetsArkiv'}
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || ('Fel: ' + res.status));
        const unloaded = (data.unloadedModels || []).length;
        const failed = (data.failedModels || []).length;
        await loadModelStatus();
        const summary = 'Återställd: ' + unloaded + (unloaded === 1 ? ' modell urladdad' : ' modeller urladdade')
            + (failed ? ', ' + failed + ' misslyckades' : '');
        modelStatus.title = summary + (data.cancelledRequests ? ' · ' + data.cancelledRequests + ' anrop avbrutna' : '');
        modelStatusText.textContent = summary;
        setTimeout(loadModelStatus, 4000);
    } catch (error) {
        modelStatus.className = 'model-status err';
        modelStatusText.textContent = 'Återställning misslyckades';
        modelStatus.title = error.message;
    } finally {
        resetOllamaBtn.disabled = false;
    }
}

resetOllamaBtn.addEventListener('click', resetOllama);

loadModelStatus();
