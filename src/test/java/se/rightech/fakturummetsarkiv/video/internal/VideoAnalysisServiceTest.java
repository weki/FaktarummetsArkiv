package se.rightech.fakturummetsarkiv.video.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.rightech.fakturummetsarkiv.video.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class VideoAnalysisServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void describeReturnsVideoAnalysisInsteadOfProcedureDraft() throws Exception {
        // Setup
        AnalysisResultStore resultStore = new AnalysisResultStore();
        FakeVideoOllamaClient fakeOllama = new FakeVideoOllamaClient();
        
        // Mocka frames
        FrameExtractorService mockFrameExtractor = new FrameExtractorService() {
            @Override
            public List<AnalyzedFrame> extractFrames(java.io.File videoFile, Path outputDir) {
                return List.of(
                    new AnalyzedFrame("base64-1", "First frame", 0, tempDir.resolve("frame-000s.png")),
                    new AnalyzedFrame("base64-2", "Second frame", 5, tempDir.resolve("frame-005s.png"))
                );
            }
        };

        // Mocka Ollama-svar för chunk-analys
        fakeOllama.setChunkResponse("""
            {
              "events": [
                {
                  "start": 0,
                  "end": 5,
                  "description": "The user opens the application menu",
                  "action": "click",
                  "typedText": null,
                  "onScreenText": ["File", "Edit", "View"],
                  "application": "Notepad",
                  "frameSecond": 2,
                  "uncertain": false
                }
              ],
              "chunkSummary": "Opening the application"
            }
            """);

        // Mocka Ollama-svar för final summary
        fakeOllama.setSummaryResponse("""
            {
              "title": "Opening Notepad application",
              "summary": "The user demonstrates how to open the Notepad application through the menu system.",
              "applications": ["Notepad"],
              "notable": [],
              "uncertainties": []
            }
            """);

        VideoAnalysisService service = new VideoAnalysisService(mockFrameExtractor, fakeOllama, resultStore);

        // Act
        VideoAnalysis result = service.describe(null, "test-video.mp4", null, msg -> {});

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.title()).isEqualTo("Opening Notepad application");
        assertThat(result.summary()).contains("Notepad application");
        assertThat(result.applications()).contains("Notepad");
        assertThat(result.events()).hasSize(1);
        
        VideoEvent event = result.events().get(0);
        assertThat(event.startSecond()).isEqualTo(0);
        assertThat(event.endSecond()).isEqualTo(5);
        assertThat(event.description()).isEqualTo("The user opens the application menu");
        assertThat(event.action()).isEqualTo("click");
        assertThat(event.typedText()).isNull();
        assertThat(event.onScreenText()).containsExactly("File", "Edit", "View");
        assertThat(event.application()).isEqualTo("Notepad");
        assertThat(event.frameSecond()).isEqualTo(2);
        assertThat(event.uncertain()).isFalse();
    }

    @Test
    void describeHandlesEmptyVideoGracefully() throws Exception {
        // Setup
        AnalysisResultStore resultStore = new AnalysisResultStore();
        FakeVideoOllamaClient fakeOllama = new FakeVideoOllamaClient();
        
        FrameExtractorService mockFrameExtractor = new FrameExtractorService() {
            @Override
            public List<AnalyzedFrame> extractFrames(java.io.File videoFile, Path outputDir) {
                return List.of(
                    new AnalyzedFrame("base64-static", "Static frame", 0, tempDir.resolve("frame-000s.png"))
                );
            }
        };

        // Mocka Ollama-svar för ingen aktivitet
        fakeOllama.setChunkResponse("""
            {
              "events": [
                {
                  "start": 0,
                  "end": 5,
                  "description": "No visible change on screen",
                  "action": null,
                  "typedText": null,
                  "onScreenText": [],
                  "application": null,
                  "frameSecond": 0,
                  "uncertain": false
                }
              ],
              "chunkSummary": "No activity detected"
            }
            """);

        fakeOllama.setSummaryResponse("""
            {
              "title": "Static screen recording",
              "summary": "The video shows a static screen with no visible activity.",
              "applications": [],
              "notable": [],
              "uncertainties": ["Unable to determine application name"]
            }
            """);

        VideoAnalysisService service = new VideoAnalysisService(mockFrameExtractor, fakeOllama, resultStore);

        // Act
        VideoAnalysis result = service.describe(null, "static-video.mp4", null, msg -> {});

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.title()).isEqualTo("Static screen recording");
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).description()).isEqualTo("No visible change on screen");
        assertThat(result.uncertainties()).contains("Unable to determine application name");
    }

    @Test
    void describeHandlesFailedChunkAnalysis() throws Exception {
        // Setup - en chunk misslyckas, en annan lyckas
        AnalysisResultStore resultStore = new AnalysisResultStore();
        FakeVideoOllamaClient fakeOllama = new FakeVideoOllamaClient();
        
        FrameExtractorService mockFrameExtractor = new FrameExtractorService() {
            @Override
            public List<AnalyzedFrame> extractFrames(java.io.File videoFile, Path outputDir) {
                // Skapa 12 frames för att få 2 chunks (chunkSize=6)
                List<AnalyzedFrame> frames = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    frames.add(new AnalyzedFrame("base64-" + i, "Frame " + i, i * 5, tempDir.resolve("frame-" + (i * 5) + "s.png")));
                }
                return frames;
            }
        };

        // Mocka Ollama att kasta fel för första chunk, lyckas för andra
        final int[] callNum = {0};
        fakeOllama.setChunkCallBehavior(new ChunkBehavior() {
            @Override
            public String getResponse() {
                callNum[0]++;
                if (callNum[0] == 1) {
                    // Första chunk-anropet misslyckas
                    throw new RuntimeException("Model failed to analyze");
                } else if (callNum[0] == 2) {
                    // Andra chunk-anropet lyckas
                    return """
                        {
                          "events": [
                            {
                              "start": 30,
                              "end": 60,
                              "description": "The application window is visible",
                              "action": null,
                              "typedText": null,
                              "onScreenText": [],
                              "application": "Notepad",
                              "frameSecond": 45,
                              "uncertain": false
                            }
                          ],
                          "chunkSummary": "Second chunk analysis"
                        }
                        """;
                } else {
                    // Summary-anropet
                    return """
                        {
                          "title": "Video analysis",
                          "summary": "Analysis completed with partial errors.",
                          "applications": ["Notepad"],
                          "notable": [],
                          "uncertainties": []
                        }
                        """;
                }
            }
        });

        VideoAnalysisService service = new VideoAnalysisService(mockFrameExtractor, fakeOllama, resultStore);

        // Act
        VideoAnalysis result = service.describe(null, "partial-error-video.mp4", null, msg -> {});

        // Assert - ska ha events från den lyckade chunk:en
        assertThat(result).isNotNull();
        assertThat(result.title()).isEqualTo("Video analysis");
        assertThat(result.events()).isNotEmpty();
        assertThat(result.uncertainties()).anyMatch(u -> u.contains("Analysis failed"));
    }

    // Interface för att styra chunk-beteende
    interface ChunkBehavior {
        String getResponse();
    }

    @Test
    void describePreservesExactTextFromOcr() throws Exception {
        // Setup
        AnalysisResultStore resultStore = new AnalysisResultStore();
        FakeVideoOllamaClient fakeOllama = new FakeVideoOllamaClient();
        
        FrameExtractorService mockFrameExtractor = new FrameExtractorService() {
            @Override
            public List<AnalyzedFrame> extractFrames(java.io.File videoFile, Path outputDir) {
                return List.of(
                    new AnalyzedFrame("base64-email", "OCR: username@example.com", 5, tempDir.resolve("frame-005s.png"))
                );
            }
        };

        // Mocka Ollama med exakt text
        fakeOllama.setChunkResponse("""
            {
              "events": [
                {
                  "start": 0,
                  "end": 5,
                  "description": "The user enters their email address",
                  "action": "input",
                  "typedText": "username@example.com",
                  "onScreenText": ["Email:", "Submit"],
                  "application": "Login Form",
                  "frameSecond": 3,
                  "uncertain": false
                }
              ],
              "chunkSummary": "Entering email"
            }
            """);

        fakeOllama.setSummaryResponse("""
            {
              "title": "Login form demonstration",
              "summary": "User enters email in login form.",
              "applications": ["Login Form"],
              "notable": [],
              "uncertainties": []
            }
            """);

        VideoAnalysisService service = new VideoAnalysisService(mockFrameExtractor, fakeOllama, resultStore);

        // Act
        VideoAnalysis result = service.describe(null, "login-video.mp4", null, msg -> {});

        // Assert - text ska vara exakt som i OCR
        assertThat(result.events().get(0).typedText()).isEqualTo("username@example.com");
        assertThat(result.events().get(0).onScreenText()).containsExactly("Email:", "Submit");
    }

    @Test
    void chunkPromptDoesNotContainProcedureLanguage() {
        // Setup
        VideoAnalysisService service = new VideoAnalysisService(null, null, null);
        
        // Act
        String prompt = service.buildDescriptionChunkPrompt(0, 10, null, "OCR text");
        
        // Assert - prompten ska INTE innehålla procedur-relaterade ord
        assertThat(prompt.toLowerCase()).doesNotContain("technical writer");
        assertThat(prompt.toLowerCase()).doesNotContain("action steps");
        assertThat(prompt.toLowerCase()).doesNotContain("imperative");
        assertThat(prompt.toLowerCase()).doesNotContain("procedure");
        assertThat(prompt.toLowerCase()).doesNotContain("prerequisites");
        
        // Prompten SKA innehålla beskrivnings-relaterade ord
        assertThat(prompt.toLowerCase()).contains("describe");
        assertThat(prompt.toLowerCase()).contains("events");
    }

    @Test
    void chunkPromptRequiresNeutralDescription() {
        // Setup
        VideoAnalysisService service = new VideoAnalysisService(null, null, null);
        
        // Act
        String prompt = service.buildDescriptionChunkPrompt(0, 10, null, "OCR text");
        
        // Assert - prompten ska kräva neutral beskrivning
        assertThat(prompt).contains("neutral");
        assertThat(prompt).contains("description");
    }

    @Test
    void chunkPromptRequiresExactTextPreservation() {
        // Setup
        VideoAnalysisService service = new VideoAnalysisService(null, null, null);
        
        // Act
        String prompt = service.buildDescriptionChunkPrompt(0, 10, null, "OCR text");
        
        // Assert - prompten ska kräva att text bevaras exakt
        assertThat(prompt.toLowerCase()).contains("exactly");
        assertThat(prompt).contains("typedText");
        assertThat(prompt).contains("onScreenText");
    }

    @Test
    void chunkPromptHandlesNoActivity() {
        // Setup
        VideoAnalysisService service = new VideoAnalysisService(null, null, null);
        
        // Act
        String prompt = service.buildDescriptionChunkPrompt(0, 10, null, "OCR text");
        
        // Assert - prompten ska hantera fall när inget händer
        assertThat(prompt).contains("No visible change");
    }

    // Fake VideoOllamaClient för testning
    private static class FakeVideoOllamaClient extends VideoOllamaClient {
        private String chunkResponse = "{}";
        private String summaryResponse = "{}";
        private Exception chunkException = null;
        private ChunkBehavior chunkBehavior = null;
        private AtomicInteger callCount = new AtomicInteger(0);

        public FakeVideoOllamaClient() {
            super("http://localhost:11434");
        }

        public String analyzeFrames(List<String> base64Images, String prompt) {
            return analyzeFrames(base64Images, prompt, null, null);
        }

        public String analyzeFrames(List<String> base64Images, String prompt, Object format, Consumer<String> progress) {
            if (chunkBehavior != null) {
                try {
                    return chunkBehavior.getResponse();
                } catch (RuntimeException e) {
                    // Om chunkBehavior kastar fel, kasta det vidare
                    throw e;
                }
            }
            int call = callCount.incrementAndGet();
            if (chunkException != null) {
                throw new RuntimeException(chunkException.getMessage());
            }
            // Första anropet är chunk-analys, sista är summary
            if (prompt.contains("summary") || prompt.contains("Summary")) {
                return summaryResponse;
            }
            return chunkResponse;
        }

        public String getModel() {
            return "test-model";
        }

        public void setChunkResponse(String response) {
            this.chunkResponse = response;
        }

        public void setSummaryResponse(String response) {
            this.summaryResponse = response;
        }

        public void setChunkException(Exception exception) {
            this.chunkException = exception;
        }

        public void setChunkCallBehavior(ChunkBehavior behavior) {
            this.chunkBehavior = behavior;
        }
    }
}
