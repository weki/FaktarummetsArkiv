# Video integration notes

## Baslinje (Fas 0)
- Branch: `feature/video-module` från `main` (commit `f6edeec`).
- `./gradlew test` (exkl. `eval`-taggade tester) är grön på baslinjen.
- `data/gpu-history.jsonl` är runtime-data och hålls utanför commit.

## SettingsSchema-kontrakt (Fas 0)
- `SettingsContractTest.correlatesProfileKeysAndDefaultsWithoutInventingSettings` kräver
  att varje icke-`spring.*`/`server.*`-nyckel i `SettingsSchema` finns i snapshot,
  och att alla nycklar i `application.properties` som ska vara inställningar registreras i schemat.
- Slutsats: alla nya `video.*`-nycklar (Fas 4) måste registreras i `SettingsSchema`,
  annars blir de inte synliga i GUI:t och tester kan brytas.
- `spring.*`/`server.*`-nycklar (uppladdningsgränser m.m.) är redan `readOnly(..., RESTART)`.

## Modulgräns (Fas 1)
- Nytt paket `se.rightech.fakturummetsarkiv.video` (publikt API) och
  `se.rightech.fakturummetsarkiv.video.internal` (allt annat).
- Regler verifieras av `VideoModuleBoundaryTest` (ArchUnit):
  1. Klasser i `video` (utanför `video.internal`) får inte bero på
     `rag`, `chat`, `grounding`, `llm`, `system` eller `markdown`.
  2. Klasser utanför `video` får inte bero på `video.internal`.
- `video` får vara bean endast när `video.enabled=true` (`@ConditionalOnProperty`).

## Grannprojekt (videointerpreter)
- Källkod i `/home/weki/IdeaProjects/videointerpreter`.
- Relevanta klasser att porta: `VideoAnalysisService`, `FrameExtractorService`,
  `VideoOllamaClient`, `OllamaChatRequest`, `AnalysisResult`, `AnalysisResultStore`,
  `VideoAnalysisController` (portas inte – ersätts av jobb-API).
- Beroenden (speglas i `build.gradle`): `javacv 1.5.11`, `ffmpeg 7.1-1.5.11:linux-x86_64`,
  `javacpp 1.5.11:linux-x86_64`, `tesseract-platform 5.5.0-1.5.11`.
- Konfiguration: se grannprojektets `application.properties` (modell `qwen3-vl:8b-instruct`,
  `ollama.num-ctx=16384`, `video.max-dim=1280`, `video.chunk-size=4`, etc.).

## Fallgropar (se TODO-listan)
1. `ollama.model` i detta projekt är textmodellen `qwen3:8b` – alla video-inställningar
   måste använda prefixet `video.ollama.*`.
2. `RetrievalService.dedupKey` kollapsar videosegment från samma fil – måste inkludera
   `chunkIndex`/`startSecond`.
3. Sökfrågan går på engelska (`rag.search-language=en`) – standard: indexera segmenttext
   på engelska.
4. VRAM: videomodellen avlastas efter jobbet; ett jobb åt gången.
5. `DocumentController.upload` använder `file.getBytes()` – stora videor måste streamas.
6. Uppladdningsgränser (`max-file-size=100MB` som standard) måste höjas.
