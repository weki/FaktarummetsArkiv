# Video integration notes

## Mål (uppdaterat)
Modulen producerar ett **procedurutkast** (titel, syfte, förkrav, numrerade steg med exakt inmatning, tidpunkt och bildruta per steg) från en skärminspelning. Resultatet sparas **inte** i vektordatabasen (ingen Qdrant/embeddings/chat). Modulen ligger i samma projekt men är avgränsad via paketet `video` och kan flyttas senare (t.ex. in i DocuForge).

## Baslinje
- Branch: `feature/video-module` från `main` (commit `f6edeec`).
- `./gradlew test` (exkl. `eval`) är grön.

## Modulgräns
- Paket `se.rightech.fakturummetsarkiv.video` (publikt API) och `video.internal`.
- Regler verifieras av `VideoModuleBoundaryTest` (ArchUnit).
- `VideoDescriber` är bean endast när `video.enabled=true`.

## Publikt API
- `VideoDescriber.draft(Path, String, VideoOptions, Consumer<String>) -> ProcedureDraft`
- `ProcedureDraft`, `ProcedureStep`, `StepKind`, `VideoOptions`, `VideoAnalysisException`

## Kvalitetsbeslut
- OCR-whitelist borttagen (behåller å/ä/ö och specialtecken).
- Varje behållen bildruta sparas som JPEG i körmappens `frames/` (frame-XXXXs.jpg).
- Standard `video.max-frames=300`.
- Per-chunk strukturerad JSON-output; slutpass för titel/syfte/förkrav/varningar.
- `inputText` bevaras ordagrant genom slutpasset (modellen får inte skriva om det).
- Videomodellen avlastas efter jobbet (keep_alive=0).

## Konfiguration
- Alla nycklar under `video.*`, `video.enabled=false` som standard.
- Se `application.properties` och `SettingsSchema` (grupp "Video").

## Grannprojekt
- `/home/weki/IdeaProjects/videointerpreter` (källa för portad kod).
- `/home/weki/IdeaProjects/DocuForge/procedure-schema.json` (målformat för fas 7, göras senare).

## Fallgropar
1. `ollama.model` är textmodellen `qwen3:8b` – video använder `video.ollama.model`.
2. OCR-whitelist tappade specialtecken – borttagen.
3. Glesa bildrutor – höjt standardtak till 300.
4. Ingen bild per steg tidigare – sparas nu i `frames/`.
5. VRAM – ett jobb åt gången, avlastning efter jobb.
6. Stora filer – streama (fas 6).
7. Uppladdningsgräns 100 MB – höjs i fas 6.
