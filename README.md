# Fakturummets Arkiv RAG

Spring Boot-tjänst för frågor mot ett lokalt dokumentarkiv. PDF:er och textfiler indexeras i
Qdrant med embeddings från Ollama, och svar genereras av en lokal chattmodell.

## Köra

```bash
./gradlew bootRun
```

Konfiguration ligger i `src/main/resources/application.properties`. Adresser till Ollama och
Qdrant kan sättas med `OLLAMA_BASE_URL` och `QDRANT_BASE_URL`, eller i den gitignorerade filen
`application-local.properties`:

```properties
ollama.base-url=http://localhost:11434
qdrant.base-url=http://localhost:6333
```

## Tester

```bash
./gradlew test
```

Utvärderingstestet (`@Tag("eval")`) körs inte som en del av den vanliga testsviten.

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

## Hybridsökning

`rag.hybrid.enabled=false` som default. När den slås på kombineras vektorsökning med ett
Qdrant-fulltextindex (engelsk tokenizer, stoppord och Snowball-stemming) via Reciprocal Rank
Fusion. Indexet skapas automatiskt första gången en kollektion söks. På exempelfrågorna i E1
förbättrades MRR från 0.87 till 1.00; utvärdera mot det egna arkivet innan den aktiveras:

```bash
QDRANT_COLLECTION=Honeywell RAG_HYBRID_ENABLED=true ./gradlew eval
```
