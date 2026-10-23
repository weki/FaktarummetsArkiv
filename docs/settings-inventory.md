# Settings inventory (round 4, G0)

Inventering gjord mot `round3/phase4` (round 2–3 inräknade). `live?` är kontrollerad i koden, inte
gissad. Med **live** menas att ett nytt värde läses vid nästa anrop utan omstart. `modelReload`
betyder att Ollama laddar modellen med nya parametrar nästa gång. `reindex` betyder att gamla
vektorer/chunks inte kan användas med det nya värdet.

## Nuvarande arkitektur

- `WorkspaceSettings` är enda runtime-overriden och ligger i `SettingsStore.current` (volatile).
  Den finns **bara i minnet** och nollställs vid omstart. Ingen `settings.json` finns.
- `GET/POST /api/chat/settings` finns och den gamla modalen använder den. `POST` är öppen och har
  ingen CSRF/Auth. `GET /api/chat/auth` returnerar alltid `{"authenticated":true}`; riktig
  autentisering saknas.
- `POST` validerar bara ett fåtal fält (topN, tröskel, temperatur, historik, språk, max 3 modeller).
  Fel returneras som ett enda `IllegalArgumentException`, inte fältvis.
- Alla `*Properties` binds av Spring vid start. Vissa läses live eftersom tjänsten anropar
  recordens accessor per request; URL:er och timeouts ligger i `RestClient` som byggs i
  konstruktorn och kräver omstart.
- Det finns ingen källa (`default|env|file|ui`) per värde, ingen etag, ingen återställning och
  ingen schemabaserad UI-generering.

## WorkspaceSettings (runtime-overrides)

Källa: UI via `POST /api/chat/settings` (minne). Standardvärden hämtas från `OllamaProperties`,
`QdrantProperties`, `RagProperties` och `ChatProperties` när `SettingsStore` skapas.

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `chatMode` | enum `chat\|query` | `chat` | `ChatService.useHistory` | Ja | UI | Direkt |
| `chatModel` | model | `ollama.model` | `ChatService` | Ja | UI | Modell laddas om vid nästa svar |
| `validatorModel` | model | `ollama.validator-model` | `ChatController` | Ja | UI | Modell laddas om vid validering |
| `validateGrounding` | boolean | `ollama.validate` | `ChatController` | Ja | UI | Direkt |
| `smartSearch` | boolean | gate konfigurerad | `ChatService` | Ja | UI | Direkt |
| `rewriteQuery` | boolean | `true` | `ChatService` | Ja | UI | Direkt |
| `collections` | lista | `[qdrant.collection]` | `ChatService`, `RetrievalService` | Ja | UI (dokumentväljare) | Direkt |
| `similarityThreshold` | decimal 0–1 | `rag.similarity-threshold` | `RetrievalService` | Ja | UI | Direkt |
| `topN` | int 1–50 | `qdrant.top-k` | `ChatService`, `RetrievalService` | Ja | UI | Direkt |
| `temperature` | decimal 0–2 | `chat.answer-temperature` | `ChatService` | Ja | UI | Direkt |
| `historySize` | int 0–100 | `rag.max-history` | `ChatService` | Ja | UI | Direkt |
| `documentIds` | lista | `null` | `ChatService`, `RetrievalService` | Ja | UI (trådens dokumentval) | Direkt |
| `embeddingModel` | model | `ollama.embedding-model` | `RetrievalService`, `DocumentService` | Ja | UI | **Reindex**; gamla vektorer kan bli oanvändbara |
| `refineModel` | model | `ollama.refine-model` | `ChatController`, `AnswerRefiner`, `FollowUpGenerator` | Ja | UI | Modell laddas om |
| `gateModel` | model | `ollama.gate-model` | `ChatService`, `SearchGate` | Ja | UI | Modell laddas om |
| `rewriteModel` | model | `ollama.rewrite-model` | `ChatService`, `QueryRewriter` | Ja | UI | Modell laddas om |
| `answerLanguage` | enum `en\|sv\|auto` | `chat.answer-language` | `ChatService`, `AnswerRefiner`, `FollowUpGenerator` | Ja | UI | Direkt |

## OllamaProperties (`ollama.*`)

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `base-url` | URL | `http://localhost:11434` | `OllamaClient` konstruktor | Nej | fil/env | **Omstart** |
| `model` | model | `""` | `SettingsStore` default; fallback i `ChatService`, `QueryRewriter`, `FollowUpGenerator`, `LlmReranker` | Ja som fallback | fil/env, UI-override | Modell laddas om |
| `embedding-model` | model | `""` | `SettingsStore` default; fallback i `RetrievalService`, `DocumentService` | Ja som fallback | fil/env, UI-override | **Reindex** |
| `timeout` | duration | `120s` (properties: `300s`) | `OllamaClient` konstruktor | Nej | fil/env | **Omstart** |
| `validator-model` | model | `""` | `SettingsStore` default; validering via `WorkspaceSettings` | Ja via override | fil/env, UI | Modell laddas om |
| `validate` | boolean | `false` | `SettingsStore` default; validering via override | Ja via override | fil/env, UI | Direkt |
| `refine-model` | model | `""` | `SettingsStore` default; fallback i `AnswerRefiner`, `FollowUpGenerator` | Ja | fil/env, UI | Modell laddas om |
| `gate-model` | model | `""` | `SettingsStore` default; fallback i `SearchGate` | Ja | fil/env, UI | Modell laddas om |
| `rewrite-model` | model | `""` | `SettingsStore` default; fallback i `QueryRewriter` | Ja | fil/env, UI | Modell laddas om |
| `chat-num-ctx` | int | `16384` (properties: `8192`) | `ChatService`, `AnswerRefiner`, `FollowUpGenerator`, `LlmReranker` | Ja | fil/env | Modell laddas om |
| `validator-num-ctx` | int | `16384` (properties: `8192`) | `GroundingValidator` | Ja | fil/env | Modell laddas om |
| `gate-num-ctx` | int | `2048` | `SearchGate` | Ja | fil/env | Modell laddas om |
| `rewrite-num-ctx` | int | `2048` | `QueryRewriter` | Ja | fil/env | Modell laddas om |
| `validator-thinking` | boolean | `false` | `GroundingValidator` | Ja | fil/env | Direkt (nästa anrop) |
| `refine-thinking` | boolean | `false` | `AnswerRefiner` | Ja | fil/env | Direkt |
| `gate-thinking` | boolean | `false` | `SearchGate` | Ja | fil/env | Direkt |
| `rewrite-thinking` | boolean | `false` | `QueryRewriter` | Ja | fil/env | Direkt |
| `followup-thinking` | boolean | `false` | `FollowUpGenerator` | Ja | fil/env | Direkt |
| `vram-gb` | decimal/null | `null` | `ModelConfigurationLogger` | Nej | fil/env | **Omstart** |

## QdrantProperties (`qdrant.*`)

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `base-url` | URL | `http://localhost:6333` | `QdrantClient` konstruktor | Nej | fil/env | **Omstart** |
| `collection` | text | `docs` | `SettingsStore` default; `QdrantClient`, `RetrievalService`, `DocumentService` | Ja som fallback | fil/env, UI (collections) | Direkt för vald collection |
| `vector-size` | int | `1024` | `QdrantClient.ensureCollection`, `DocumentService` | Ja (men farligt) | fil/env | **Reindex**; måste matcha embeddingmodellen |
| `distance` | text | `Cosine` | `QdrantClient.ensureCollection` | Ja vid skapande | fil/env | **Reindex**/ny collection |
| `top-k` | int | `5` | `SettingsStore` default; retrieval via `topN` | Ja via override | fil/env, UI | Direkt |
| `timeout` | duration | `10s` | `QdrantClient` konstruktor | Nej | fil/env | **Omstart** |
| `query-prefix` | text | `query: ` | `QdrantProperties.queryInput`, `RetrievalService` | Ja | fil/env | **Reindex** om embeddings påverkas |
| `passage-prefix` | text | `passage: ` | `QdrantProperties.passageInput`, `DocumentService` | Ja | fil/env | **Reindex** om embeddings påverkas |

## RagProperties (`rag.*`)

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `max-history` | int | `20` | `SettingsStore` default; `ChatService` fallback | Ja | fil/env, UI (historySize) | Direkt |
| `chunk-size` | int | `1200` | `Chunker` | Ja | fil/env | Endast **nya dokument**; reindex för gamla |
| `chunk-overlap` | int | `200` | `Chunker` | Ja | fil/env | Endast **nya dokument**; reindex för gamla |
| `max-source-chars` | int | `8000` | `ChatService`, `GroundingValidator`, `AnswerRefiner`, `FollowUpGenerator` | Ja | fil/env | Direkt |
| `search-language` | text | `en` | `ChatService`, `SearchGate`, `QueryRewriter` | Ja | fil/env | Direkt |
| `similarity-threshold` | decimal 0–1 | `0.3` | `SettingsStore` default; retrieval via override | Ja via override | fil/env, UI | Direkt |
| `max-history-chars` | int | `4000` | `ChatService` | Ja | fil/env | Direkt |
| `gate.mode` | enum `model\|rules\|off` | `model` | `SearchGate`, `SettingsStore` | Ja | fil/env | Direkt |

## ChatProperties (`chat.*`)

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `answer-temperature` | decimal 0–2 | `0.1` | `SettingsStore` default; `ChatService` via override | Ja via override | fil/env, UI | Direkt |
| `thinking` | boolean | `true` | `ChatService` | Ja | fil/env | Direkt |
| `answer-language` | enum `en\|sv\|auto` | `en` | `SettingsStore` default; `ChatService`, `AnswerRefiner`, `FollowUpGenerator` | Ja via override | fil/env, UI | Direkt |

## HybridProperties (`rag.hybrid.*`)

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `enabled` | boolean | `false` | `RetrievalService` | Ja | fil/env | Direkt |
| `keyword-limit` | int | `50` | `RetrievalService` | Ja | fil/env | Direkt |
| `rrf-k` | int | `60` | `RetrievalService` | Ja | fil/env | Direkt |

## RerankProperties (`rag.rerank.*`)

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `enabled` | boolean | `false` | `RetrievalService` | Ja | fil/env | Direkt |
| `candidates` | int | `25` | `RetrievalService` | Ja | fil/env | Direkt |
| `model` | model | `""` (fallback `ollama.model`) | `LlmReranker`, `ModelConfigurationLogger` | Ja som fallback | fil/env | Modell laddas om |
| `max-chars` | int | `600` | `LlmReranker` | Ja | fil/env | Direkt |

## ArchiveProperties (`archive.*`)

| Key | Typ | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|---|
| `pdf-roots` | lista | `""` | `PdfService` (valideras i konstruktor), `allowedRoots()` | Nej (valideras vid start) | fil/env | **Omstart** |
| `upload-dir` | text | `archive` | `FileStore.uploadPath`, `PdfService` | Nej (bunden vid start; läses per anrop) | fil/env | **Omstart** |

## Spring/infrastruktur (inte `*Properties`, men relevanta)

| Key | Default | Läses i | Live? | Ändras av | Vid ändring |
|---|---|---|---|---|---|
| `spring.servlet.multipart.max-file-size` | `100MB` | Spring multipart | Nej | fil/env | **Omstart** |
| `spring.servlet.multipart.max-request-size` | `500MB` | Spring multipart | Nej | fil/env | **Omstart** |
| `server.tomcat.max-swallow-size` | `512MB` | Tomcat | Nej | fil/env | **Omstart** |
| `spring.codec.max-in-memory-size` | `512MB` | Spring codec | Nej | fil/env | **Omstart** |
| `spring.config.import` | `optional:file:./application-local.properties` | Spring config | Nej | fil/env | **Omstart** |

## Luckor och avvikelser

- Ingen persistent settingsfil, ingen atomisk skrivning, ingen korrupt-fil-hantering, ingen
  schema-version, ingen etag och ingen källa per värde.
- Ingen `EffectiveSettings`-tjänst. `SettingsStore` är fortfarande enda överriden och är bara i minnet.
- Ingen schema/registry, ingen `GET/PUT/DELETE /api/settings`, ingen fältvis validering och ingen
  `If-Match`/`409`.
- Ingen riktig autentisering eller CSRF. `POST /api/chat/settings` är öppen på alla adresser.
- Ingen read-only-regel för infrastruktur, ingen maskning, ingen export/import.
- Ingen `procedure top-k` och ingen procedure-retrieval i koden.
- Ingen modellstorlek i `GET /api/chat/models`; ingen `/api/ps`-vy, ingen anslutningsdiagnostik.
- Max 3-modellsregeln finns i `SettingsStore`, men räknar inte in `rag.rerank.model`. Startloggen
  räknar däremot in den. Regeln bör samma källa i round 4.
- Embeddingdimension kontrolleras först vid indexering, inte vid start eller i UI.
- `chunk-overlap < chunk-size` är inte validerat; `Chunker` klampar tyst overlap.
- `num_ctx` kontra källbudget varnas bara i `ChatService` när prompten redan är stor.
- `GET /api/chat/models` returnerar bara namn, inte storlek/VRAM.
- Den gamla modalen saknar hybrid, rerank, thinking, num_ctx, chunk-inställningar, källor för
  värden, apply-läge och återställning.
