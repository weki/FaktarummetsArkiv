# Plan: En kollektion (faktarummet) med payload-baserad filtrering

## Mål
Gå från flera Qdrant-kollektioner till en enda kollektion ("faktarummet") där dokument
skiljs åt via payload-fälten `collection` (källsystem: Honeywell, Aspen, Genua) och `tags`.

## Krav (uppdaterat)
- **Default**: alla källsystem och taggar ingår i sökningen (ingen filtrering).
- **Per tråd**: användaren kan begränsa sökningen till specifika källsystem och/eller taggar,
  precis som idag med Qdrant-kollektioner. Tomt val = allt (default).

- Qdrant-kollektioner = separata databaser (t.ex. docs, Honeywell, Aspen).
- `DocumentService.collections()` returnerar Qdrant-kollektionsnamn.
- `RetrievalService.retrieve()` söker i en eller flera Qdrant-kollektioner parallellt.
- Scope-dialogen i chatten låter användaren välja Qdrant-kollektioner per tråd.
- `DocumentInfo.collection` = Qdrant-kollektionsnamn (visas i dokumentlistan).

## Fas 1: Datamodell och backend
1. **`qdrant.collection`** blir den enda kollektionen ("faktarummet" / docs). Alla dokument
   indexeras där.
2. **`DocumentService.index()`**: anropa alltid `ensureCollection(qdrant.collection())` i stället
   för den valda kollektionen. Payload-fältet `collection` sätts till det valda källsystemet
   (Honeywell/Aspen/Genua) — det är redan så.
3. **`DocumentService.collections()`**: returnera distinct värden av payload-fältet `collection`
   (källsystem) i stället för Qdrant-kollektionsnamn. Byt namn till `sourceSystems()` eller
   behåll `collections()` men ändra semantik.
4. **`DocumentService.tags()`**: redan implementerat — returnerar alla taggar.
5. **`DocumentController`**: `/collections` → `/source-systems` (eller behåll path men ändra
   innehåll). Lägg till `/tags` (finns redan).
6. **`findByContentHash()`**: sök i den enda kollektionen i stället för att loopa över flera.
7. **`delete()`**: radera ur den enda kollektionen i stället för att loopa.

## Fas 2: Retrieval och filtrering
8. **`RetrievalService.retrieve()`**: acceptera filter på `collection` (källsystem) och `tags`
   i stället för Qdrant-kollektionsnamn. Bygg Qdrant-filter med `must` på `collection` och
   `tags` (match any).
9. **`ChatService.streamChat()`**: skicka `collection`- och `tags`-filter i stället för
   `collections`-lista. Eller behåll `collections`-parametern men tolka den som källsystem.
10. **`ChatRequest`**: byt `collections` till `sourceSystems` + `tags` (eller behåll namn men
    ändra semantik). Uppdatera `WorkspaceSettings.collections` → `sourceSystems`/`tags`.

## Fas 3: Frontend
11. **Scope-dialogen** (`scope-dialog.js`): visa källsystem (Honeywell, Aspen, Genua) och
    taggar i stället för Qdrant-kollektioner. Checkboxar för source systems + taggar.
12. **Thread store** (`threads-store.js`): spara `sourceSystems` och `tags` i stället för
    `collections`.
13. **Stream** (`stream.js`): skicka de nya fälten.
14. **Dokumentlistan** (`list.js`): `collection`-kolumnen visar källsystem (som den redan gör).

## Fas 4: Migrering av befintliga data
15. Script eller endpoint som flyttar punkter från gamla Qdrant-kollektioner till den nya
    "faktarummet"-kollektionen, med `collection`-payloadfält satt.
16. Backup av gamla kollektioner innan migrering.

## Fas 5: Dokumentation
17. README: förklara en kollektion + payload-filter.
18. settings-inventory.md: uppdatera.

## Berörda filer
Backend:
- `DocumentService.java` (collections, index, delete, findByContentHash)
- `RetrievalService.java` (retrieve, resolveCollections, toSource)
- `DocumentController.java` (endpoints)
- `ChatService.java` (effectiveCollections)
- `ChatController.java` (ChatRequest)
- `WorkspaceSettings.java` (collections → sourceSystems/tags)
- `SettingsStore.java` (collections handling)
- `EffectiveSettings.java` (workspace())

Frontend:
- `scope-dialog.js`
- `threads-store.js`
- `stream.js`
- `list.js` (kanske)
- `upload.js` (collection-val vid uppladdning = källsystem)

## Risker
- Befintliga dokument i gamla kollektioner måste migreras.
- `collection`-fältet används redan i payload — bra, ingen schemaändring behövs.
- Filtrering på `tags` kräver Qdrant-filter med `match: {any: [...]}`.
- Backward compat: om `collections` fortfarande skickas, tolka som source systems.

## Beslut behövs
- Ska `collection`-fältet (källsystem) vara obligatoriskt vid uppladdning?
- Ska taggar kunna filtreras med AND eller OR?
- Ska den gamla `collections`-inställningen i settings.json tas bort eller mappas?
