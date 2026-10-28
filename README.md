# Fakturummets Arkiv RAG

Lokal RAG-tjänst för frågor mot ett dokumentarkiv. PDF:er och textfiler indexeras i
[Qdrant](https://qdrant.tech/) med embeddings från [Ollama](https://ollama.com/), och svar
genereras av en lokal chattmodell. Allt kan köras utan molntjänster.

Tjänsten innehåller ett webbgränssnitt, dokumentuppladdning, källhänvisningar, validering av
svar, följdfrågor, en schemabaserad inställningssida, hybridsökning och LLM-baserad reranking (de
två sista är opt-in).

## Krav

- Java 21
- Ollama med modellerna nedan
- Qdrant 1.18 eller senare (behöver inte vara igång före första start om du använder
  `docker compose`, se nedan)

### Ollama-modeller

Appens standardkonfiguration använder en chattmodell för alla roller plus en embeddingmodell:

```bash
ollama pull qwen3:8b
ollama pull qllama/multilingual-e5-large
```

- `qwen3:8b` – chatt, validering, förfining, sök-gate, omformulering, följdfrågor och rerank
- `qllama/multilingual-e5-large` – embeddings (1024 dimensioner)

Alla roller ärver `ollama.model`. Du kan ge en enskild roll en egen modell i
`src/main/resources/application.properties` eller under **Avancerat: modell per roll** i
inställningarna. Ett tomt värde betyder att rollen ärver huvudmodellen. Samma sak gäller
`num_ctx`: rollen ärver `ollama.chat-num-ctx` om den inte har ett eget värde.

Vid start loggas vilka distinkta modeller som används och om en roll ärver eller har ett eget
värde. Om fler än två modeller är konfigurerade och `ollama.vram-gb` är satt loggas en varning.
Ingen modell blockeras, men fler modeller kan göra att Ollama laddar om mellan anropen.

Vill du använda en liten modell för gate och rewrite, sätt till exempel:

```properties
ollama.gate-model=phi3.5:latest
ollama.rewrite-model=phi3.5:latest
```

Profilen `two-models` finns kvar för den som vill låta gate och rewrite använda chattmodellen:

```bash
SPRING_PROFILES_ACTIVE=two-models ./gradlew bootRun
```

Profilen är opt-in och ändrar inte standardkonfigurationen.

## Modeller och kontext

En modell och ett kontextfönster är standard för alla roller:

```properties
ollama.model=qwen3:8b
ollama.chat-num-ctx=16384
```

Per-roll-inställningarna är tomma som standard och ärver då huvudmodellen respektive
`ollama.chat-num-ctx`:

```properties
# Tomt = ärv ollama.model
ollama.validator-model=
ollama.refine-model=
ollama.gate-model=
ollama.rewrite-model=
ollama.followup-model=
rag.rerank.model=

# Tomt/0 = ärv ollama.chat-num-ctx
ollama.validator-num-ctx=
ollama.gate-num-ctx=
ollama.rewrite-num-ctx=
```

Följdfrågor använder `ollama.followup-model` om den är satt, annars förfiningsmodellen, annars
chattmodellen. Förfining, följdfrågor och rerank använder alltid chattens kontextfönster.

Inställningssidan visar det effektiva värdet och markerar ärvda värden. Äldre sparade värden som är
lika med chattmodellen eller chattens kontext behandlas som ärvda när filen läses in; filen skrivs
inte om förrän du sparar. Ett eget värde som skiljer sig från chatten behålls.

Samma modell med olika `num_ctx` för olika roller får Ollama att ladda om modellen mellan anropen.
Sidan och startloggen varnar för det. En liten modell (under ca 4B parametrar) räcker oftast för
sök-gate och omformulering och lämnar mer VRAM till huvudmodellen.

## VRAM och kontextfönster

Alla roller ärver `ollama.chat-num-ctx=16384` som standard, så samma modell laddas normalt inte om
med olika kontext. Ett mindre `num_ctx` ger mindre KV-cache, särskilt för modeller utan GQA. Om du
ger gate och rewrite egna mindre fönster, sätt dem uttryckligen:

```properties
ollama.gate-num-ctx=2048
ollama.rewrite-num-ctx=2048
```

Modellnamn, prompts och indexerade data är oförändrade; ingen omindexering behövs. Vid start loggas
modell och `num_ctx` per roll, med `(egen)` eller `(ärvd)`.

Verifiering kräver en maskin med Ollama och GPU. Jag har inte kunnat köra eller mäta VRAM i den här
miljön. Gör så här hos dig:

1. Starta appen och titta efter loggraderna `Rollkonfiguration gate` och
   `Rollkonfiguration rewrite` – de ska visa om värdet är eget eller ärvt.
2. Ställ en fråga som kräver sökning och kör `ollama ps` före och efter.
3. Kontrollera att modellerna inte laddas om varje gång. Om du satt egna små kontextfönster ska
   gate- och rewritemodellen visa dem i `ollama ps`.
4. Om modellerna fortfarande trängs ut, mät med `ollama ps` och justera kontextstorlekarna utifrån
   mätningen i stället för att gissa.

## Köra lokalt

Starta Qdrant om du inte redan har en instans:

```bash
docker run --rm -p 6333:6333 -p 6334:6334 -v qdrant-data:/qdrant/storage qdrant/qdrant:v1.18.2
```

Starta sedan appen:

```bash
./gradlew bootRun
```

Öppna <http://localhost:8080>. Kollektionen skapas automatiskt vid första uppladdningen.

### Miljövariabler

| Variabel | Standard | Beskrivning |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Adress till Ollama |
| `QDRANT_BASE_URL` | `http://localhost:6333` | Adress till Qdrant |
| `QDRANT_COLLECTION` | `docs` | Standardkollektion |
| `CHAT_THINKING` | `true` | Skicka `think` till qwen3-modeller |

Övriga inställningar kan sättas i `src/main/resources/application.properties` eller i den
gitignorerade filen `application-local.properties`:

```properties
ollama.base-url=http://localhost:11434
qdrant.base-url=http://localhost:6333
qdrant.collection=docs
```

Uppladdade original sparas i `archive/` (kan ändras med `archive.upload-dir`). PDF-filer utanför
den mappen kan läsas om deras rötter anges i `archive.pdf-roots` (kommaseparerat).

## Docker Compose

`docker-compose.yml` startar appen och Qdrant:

```bash
docker compose up --build
```

Ollama körs normalt på värden. Compose pekar appen mot
`http://host.docker.internal:11434`; sätt `OLLAMA_BASE_URL` om Ollama finns någon annanstans.
Originalfiler hamnar i `./archive` och Qdrants data i volymen `qdrant-data`.

## Inställningar

Öppna <http://localhost:8080/settings.html> eller klicka på **Inställningar**. Sidan genereras från
en central schemafil i koden och visar effektivt värde, standardvärde, källa
(`Standard`, `Miljö`, `Fil` eller `UI`), när ändringen slår igenom och vilka värden som är
skrivskyddade.

Ändringar från sidan sparas som överridningar i `settings.json`. Filen ligger som standard bredvid
arkivet (t.ex. `./settings.json`) och kan flyttas med `settings.file`:

```properties
settings.file=/srv/fakturum/settings.json
```

Filen innehåller bara överridningar, inte alla standardvärden:

```json
{
  "version": 1,
  "overrides": {
    "ollama.model": "qwen3:8b",
    "rag.rerank.enabled": true
  }
}
```

Prioritetsordning är `UI-override > application-local.properties > miljövariabler >
application.properties`. En tom fil eller ingen fil betyder att appen beter sig precis som den
konfiguration som redan finns. Skrivningen är atomisk, och en fil som inte kan läsas flyttas till
`settings.json.bad` i stället för att appen startar med halva värden. `settings.json` är
gitignorerad eftersom den kan innehålla lokal driftkonfiguration.

Värden som kräver omstart (`RESTART`) och infrastruktur (Ollama/Qdrant-adresser, timeouts,
vektordimension, distansmått, prefix, arkivsökvägar och Spring/Tomcat-gränser) visas men kan bara
ändras i konfigurationen. Hemliga värden ska aldrig läggas i `settings.json`.

Vid modellbyte varnar sidan om fler än två distinkta modeller är konfigurerade när `ollama.vram-gb`
är satt, och om samma modell används med olika `num_ctx`. Ett byte av embeddingmodell eller
vektordimension kräver omindexering; sidan varnar men startar aldrig någon omindexering. Chunkstorlek
och överlapp påverkar nya dokument direkt, medan gamla dokument måste indexeras om för att få den
nya chunkningen. En modell som inte finns installerad i Ollama ger en varning men sparas ändå.

Skrivningar till inställnings-API:t tillåts bara från serverns egen dator och kräver headern
`X-Requested-With: FakturummetsArkiv`. Läsning fungerar även från andra datorer i nätverket. Det
finns ingen inloggning; exponera inte appen mot untrusted nätverk.

### Återställ Ollama

Knappen **Återställ** bredvid modellstatusen i sidomenyn (`POST /api/chat/ollama/reset`) avbryter
alla pågående anrop från appen till Ollama och laddar ur alla modeller som Ollama har i minnet
(`keep_alive: 0`), så att nästa fråga laddar dem på nytt. Själva Ollama-processen startas inte om;
hänger den helt måste den startas om på värden. Endpointen har samma begränsning som
inställningsskrivningar (bara från serverns egen dator, med headern ovan).

`ollama.timeout` gäller som tid utan svar: ett anrop avbryts om Ollama inte skickat något på så
lång tid. Strömmade svar kan alltså pågå längre så länge tokens fortsätter komma.

## Tester

```bash
./gradlew test
```

Utvärderingstestet (`@Tag("eval")`) körs inte som en del av den vanliga testsviten. CI kör
`./gradlew build`, vilket även bygger ett körbart jar-paket.

## Utvärdering (E1/T1/T2)

Harnesset mäter både retrieval och det faktiska svaret mot en körande Ollama och Qdrant. Frågorna
ligger i `src/test/resources/eval/questions.jsonl`, ett JSON-objekt per rad:

```json
{"id": "en-example", "lang": "en", "question": "...", "expectedDocuments": ["del-av-filnamn"], "expectedPhrases": ["..."], "expectedFacts": ["..."], "expectedCitations": ["dokumenttitel"], "expectNoAnswer": false, "notes": "..."}
```

- `expectedDocuments` matchas mot källans `source`/`title` (skiftlägesokänsligt, delsträng).
- `expectedPhrases` mäter hur stor del av förväntade fraser som finns i hämtade textstycken.
- `expectedFacts` mäter **factual recall** i det genererade svaret.
- `expectedCitations` mäter **citation recall/precision** mot de källor som svaret faktiskt citerar.
- `expectNoAnswer` mäter om exakt `I cannot find that information in the documents.` returneras.
- `notes` är fritt för ägarens kommentarer.

Frågorna är **exempel som ägaren ska ersätta** med frågor, förväntade fakta och dokument från det
egna arkivet. Använd minst 20–30 frågor per språk, blanda faktafrågor, följdfrågor och frågor som
inte ska besvaras, och ändra inte frågorna när ni jämför före/efter en ändring.

Kör mot lokala tjänster (samma konfiguration som appen). Ange kollektion om den inte heter `docs`:

```bash
QDRANT_COLLECTION=Honeywell ./gradlew eval
```

Exempel på utskrift:

```
Question               Lang Rank   Recall  Phrase  Fact    CitRec  CitPrec NoAns
------------------------------------------------------------------------------------
en-icon-console        en   1      1.00    1.00    1.00    1.00    1.00    yes
sv-t5810xl             sv   1      1.00    0.50    1.00    1.00    1.00    yes

Language  Questions Recall@5  MRR     Phrase
------------------------------------------------
en        3         1.00      0.78    1.00
sv        3         1.00      1.00    0.75
all       6         1.00      0.87    0.90

Language  Questions Fact     CitRec  CitPrec  NoAns
--------------------------------------------------------
all       6         0.92     0.83    0.75     1.00
```

Varje körning skriver också `eval/baseline-<timestamp>.json` med konfiguration (modeller,
kontextstorlekar, top-k, tröskel och flaggor) och per-fråga-resultat. Filen är gitignored och ska
inte committas. Jämför alltid mot samma frågeuppsättning och samma modeller; en enskild körning
säger inget om kvaliteten.

### Begränsningar

- Harnesset kräver Ollama och Qdrant med indexerade dokument. CI kör bara `./gradlew build`; den
  vanliga testsviten exkluderar `@Tag("eval")`.
- Retrieval- och svarsmått mäter det egna urvalet, inte generell kvalitet. Små urval ger stor
  slumpvariation.
- `expectedFacts` matchas som normaliserad delsträng. Formuleringar som betyder samma sak men
  använder andra ord räknas inte som träff.
- `expectedCitations` matchas mot källtitel/source. Citeringssiffror kan ändras mellan körningar
  eftersom de följer källordningen.
- Brier-poängen beräknas bara när validering är på och domaren gav `supported`; den är en grov
  kalibreringssignal, inte en sannolikhet.
- Körningen ändrar inga dokument eller index. Den anropar däremot Ollama och kan ta lång tid.

Använd resultatet före och efter en ändring för att avgöra om ändringen förbättrar retrieval eller
svar, t.ex. innan hybrid search eller reranking slås på.

## Svar och thinking

`chat.answer-temperature=0.1` håller svaren faktainriktade; temperaturen kan också ändras per
workspace i gränssnittet. `chat.thinking=true` skickar `think` till Ollama för själva svaret, så
qwen3-modeller får resonera i `<think>`-taggen. Stäng av med `CHAT_THINKING=false` om modellen inte
stöder det eller om svarstiden är viktigare än resonemanget.

Thinking kan styras per roll. Standard är att bara svaret använder thinking:

```properties
chat.thinking=true
ollama.validator-thinking=false
ollama.refine-thinking=false
ollama.gate-thinking=false
ollama.rewrite-thinking=false
ollama.followup-thinking=false
```

Aktivera t.ex. gate-thinking med `ollama.gate-thinking=true`. Även om en modell skickar
`<think>`-block trots att thinking är av strippas de bort innan svaret används.

## Kontextbudget

`ollama.chat-num-ctx` är 16384 som standard och ärvs av alla roller. Det räcker för
`rag.max-source-chars=8000` (cirka 2 500 tokens) plus systemprompt, fråga, svar och thinking, men
historiken måste begränsas. Därför trimmas historiken till `rag.max-history-chars=4000` tecken;
äldsta meddelandet tas bort först. Om den uppskattade prompten ändå överstiger `num_ctx`, eller
ligger nära gränsen med reserv för svar/thinking, loggas en varning vid den aktuella frågan.

## Sökgate

`rag.gate.mode` styr om sökgaten används:

- `model` (default) – modellen avgör om sökning behövs
- `rules` – sökning hoppas bara över för tydlig small talk på svenska eller engelska, högst fyra
  ord och utan frågetecken; allt annat söker
- `off` – söker alltid

Vid osäkerhet söker systemet alltid. Uppföljningar som `och då?` räknas aldrig som small talk.

## Hybridsökning

`rag.hybrid.enabled=false` som default. När den slås på kombineras vektorsökning med ett
Qdrant-fulltextindex (engelsk tokenizer, stoppord och Snowball-stemming) via Reciprocal Rank
Fusion. Indexet skapas automatiskt första gången en kollektion söks. På exempelfrågorna i E1
förbättrades MRR från 0.87 till 1.00; utvärdera mot det egna arkivet innan den aktiveras:

```bash
QDRANT_COLLECTION=Honeywell RAG_HYBRID_ENABLED=true ./gradlew eval
```

## Reranking

`rag.rerank.enabled=false` som default. När den slås på hämtas `rag.rerank.candidates`
(25) kandidater och en LLM-bedömning via Ollama rangordnar dem innan `topN` returneras.
`rag.rerank.model` tom betyder att `ollama.model` används, så ingen fjärde modell laddas.
På exempelfrågorna i E1 förbättrades MRR från 0.87 till 1.00:

```bash
QDRANT_COLLECTION=Honeywell RAG_RERANK_ENABLED=true ./gradlew eval
```

## Indexering och dubbletter

Uppladdning dedupliceras på SHA-256 av filinnehållet. Ett identiskt dokument indexeras inte igen,
och svaret anger att nya taggar eller kollektioner inte tillämpades. Originalfilen sparas först
när innehållet har kunnat läsas och chunkas; om indexeringen misslyckas raderas den. När ett
dokument tas bort raderas även originalfilen.

## Licens

Licens är inte vald ännu. Fråga ägaren innan projektet återanvänds eller publiceras.
