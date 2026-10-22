# Fakturummets Arkiv RAG

Lokal RAG-tjänst för frågor mot ett dokumentarkiv. PDF:er och textfiler indexeras i
[Qdrant](https://qdrant.tech/) med embeddings från [Ollama](https://ollama.com/), och svar
genereras av en lokal chattmodell. Allt kan köras utan molntjänster.

Tjänsten innehåller ett webbgränssnitt, dokumentuppladdning, källhänvisningar, validering av
svar, följdfrågor, hybridsökning och LLM-baserad reranking (de två sista är opt-in).

## Krav

- Java 21
- Ollama med modellerna nedan
- Qdrant 1.18 eller senare (behöver inte vara igång före första start om du använder
  `docker compose`, se nedan)

### Ollama-modeller

Appens standardkonfiguration använder tre modeller:

```bash
ollama pull qwen3:8b
ollama pull qllama/multilingual-e5-large
ollama pull phi3.5:latest
```

- `qwen3:8b` – chatt, svarsvalidering, förfining och följdfrågor
- `qllama/multilingual-e5-large` – embeddings (1024 dimensioner)
- `phi3.5:latest` – sök-gate och omformulering av sökfrågor

Modellerna kan bytas i `src/main/resources/application.properties` eller i inställningarna i
webbgränssnittet.

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

## Tester

```bash
./gradlew test
```

Utvärderingstestet (`@Tag("eval")`) körs inte som en del av den vanliga testsviten. CI kör
`./gradlew build`, vilket även bygger ett körbart jar-paket.

## Retrieval-utvärdering (E1)

Harnesset mäter **recall@5** och **MRR** per språk mot en körande Ollama och Qdrant. Frågorna
ligger i `src/test/resources/eval/questions.jsonl`, ett JSON-objekt per rad:

```json
{"id": "en-example", "lang": "en", "question": "...", "expectedDocuments": ["del-av-filnamn"], "expectedPhrases": ["..."]}
```

`expectedDocuments` matchas mot källans `source`/`title` (skiftlägesokänsligt, delsträng), och
`expectedPhrases` mäter hur stor del av förväntade fraser som finns i de hämtade textstyckena.

De fem medföljande frågorna är **exempel som ägaren ska ersätta** med frågor och förväntade
dokument från det egna arkivet.

Kör mot lokala tjänster (samma konfiguration som appen). Ange vilken Qdrant-kollektion som ska
utvärderas om den inte heter `docs`:

```bash
QDRANT_COLLECTION=Honeywell ./gradlew eval
```

Exempel på utskrift:

```
Language  Questions Recall@5  MRR     Phrase
------------------------------------------------
en        3         1.00      0.78    1.00
sv        2         1.00      1.00    0.75
all       5         1.00      0.87    0.90
```

Använd resultatet före och efter en ändring för att avgöra om ändringen förbättrar retrieval,
t.ex. innan hybrid search eller reranking slås på.

## Svar och thinking

`chat.answer-temperature=0.1` håller svaren faktainriktade; temperaturen kan också ändras per
workspace i gränssnittet. `chat.thinking=true` skickar `think` till Ollama, så qwen3-modeller får
resonera i `<think>`-taggen. Stäng av med `CHAT_THINKING=false` om modellen inte stöder det eller
om svarstiden är viktigare än resonemanget.

```bash
CHAT_THINKING=false ./gradlew bootRun
```

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
