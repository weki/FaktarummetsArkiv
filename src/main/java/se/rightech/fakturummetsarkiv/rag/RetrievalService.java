package se.rightech.fakturummetsarkiv.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;
import se.rightech.fakturummetsarkiv.chat.ChatProperties;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final OllamaClient llm;
    private final OllamaProperties ollama;
    private final QdrantProperties qdrant;
    private final QdrantClient qdrantClient;
    private final SettingsStore settingsStore;
    private final HybridProperties hybrid;
    private final Reranker reranker;
    private final RerankProperties rerank;
    private final EffectiveSettings effective;

    @Autowired
    public RetrievalService(OllamaClient llm, OllamaProperties ollama, QdrantProperties qdrant,
                            QdrantClient qdrantClient, SettingsStore settingsStore, HybridProperties hybrid,
                            Reranker reranker, RerankProperties rerank, EffectiveSettings effective) {
        this.llm = llm;
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.qdrantClient = qdrantClient;
        this.settingsStore = settingsStore;
        this.hybrid = hybrid;
        this.reranker = reranker;
        this.rerank = rerank;
        this.effective = effective;
    }

    public RetrievalService(OllamaClient llm, OllamaProperties ollama, QdrantProperties qdrant,
                            QdrantClient qdrantClient, SettingsStore settingsStore, HybridProperties hybrid,
                            Reranker reranker, RerankProperties rerank) {
        this(llm, ollama, qdrant, qdrantClient, settingsStore, hybrid, reranker, rerank,
                new EffectiveSettings(ollama, qdrant,
                        new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model"),
                        new ChatProperties(0.1, true, "en"), hybrid, rerank));
    }

    public List<Map<String, Object>> retrieve(String query, List<String> collections, int topK, Double scoreThreshold) {
        return retrieve(query, collections, topK, scoreThreshold, null);
    }

    public List<Map<String, Object>> retrieve(String query, List<String> collections, int topK, Double scoreThreshold,
                                          List<String> documentIds) {
        String model = settingsStore.get().embeddingModel();
        if (model == null || model.isBlank()) {
            model = effective.string("ollama.embedding-model");
        }
        if (model == null || model.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        double[] vector = llm.embedOne(model, qdrant.queryInput(model, query));
        if (vector.length == 0) {
            return List.of();
        }

        List<String> targets = resolveCollections(collections);
        if (targets.contains(qdrant.collection())) {
            qdrantClient.ensureCollection();
        }

        List<Map<String, Object>> sources;
        if (effective.bool("rag.hybrid.enabled")) {
            sources = hybridSearch(query, vector, targets, topK, scoreThreshold, documentIds);
        } else {
            sources = denseSearch(vector, targets, topK, scoreThreshold, documentIds);
        }

        List<Map<String, Object>> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> source : sources) {
            String key = dedupKey(source);
            if (seen.add(key)) {
                deduped.add(source);
            }
        }
        List<Map<String, Object>> ranked = deduped;
        if (effective.bool("rag.rerank.enabled") && deduped.size() > 1) {
            int candidates = Math.max(topK, effective.integer("rag.rerank.candidates"));
            List<Map<String, Object>> shortlist = deduped.size() > candidates
                    ? deduped.subList(0, candidates) : deduped;
            List<Map<String, Object>> reranked = reranker.rerank(query, shortlist, topK);
            if (reranked != null && !reranked.isEmpty()) {
                ranked = reranked;
            }
        }
        if (ranked.size() > topK) {
            ranked = ranked.subList(0, topK);
        }

        int neighborChunks = effective.integer("rag.neighbor-chunks");
        if (neighborChunks > 0) {
            ranked = expandNeighbors(ranked, targets, neighborChunks);
        }
        return ranked;
    }

    private List<Map<String, Object>> denseSearch(double[] vector, List<String> targets, int topK,
                                                  Double scoreThreshold, List<String> documentIds) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String collection : targets) {
            for (QdrantClient.ScoredPoint hit : qdrantClient.search(collection, vector, topK, scoreThreshold,
                    documentIds)) {
                sources.add(toSource(collection, hit.payload(), hit.score()));
            }
        }
        sources.sort(Comparator.comparingDouble((Map<String, Object> source) ->
                ((Number) source.get("score")).doubleValue()).reversed());
        return sources;
    }

    private List<Map<String, Object>> hybridSearch(String query, double[] vector, List<String> targets, int topK,
                                                   Double scoreThreshold, List<String> documentIds) {
        int candidates = Math.max(topK * 4, 20);
        Map<String, FusedHit> fused = new LinkedHashMap<>();

        for (String collection : targets) {
            qdrantClient.ensureTextIndex(collection);
            List<QdrantClient.ScoredPoint> dense = qdrantClient.search(collection, vector, candidates,
                    scoreThreshold, documentIds);
            for (int i = 0; i < dense.size(); i++) {
                QdrantClient.ScoredPoint hit = dense.get(i);
                addFused(fused, hit, collection, 1.0 / (effective.integer("rag.hybrid.rrf-k") + i + 1));
            }
            List<QdrantClient.ScoredPoint> keyword = qdrantClient.keywordSearch(collection, query,
                    effective.integer("rag.hybrid.keyword-limit"), documentIds);
            for (int i = 0; i < keyword.size(); i++) {
                QdrantClient.ScoredPoint hit = keyword.get(i);
                addFused(fused, hit, collection, 1.0 / (effective.integer("rag.hybrid.rrf-k") + i + 1));
            }
        }

        List<FusedHit> ranked = new ArrayList<>(fused.values());
        ranked.sort(Comparator.comparingDouble(FusedHit::rrf).reversed());

        List<Map<String, Object>> sources = new ArrayList<>();
        for (FusedHit hit : ranked) {
            Map<String, Object> source = toSource(hit.collection(), hit.payload(), hit.rawScore());
            source.put("score", hit.rrf());
            sources.add(source);
        }
        return sources;
    }

    private static void addFused(Map<String, FusedHit> fused, QdrantClient.ScoredPoint hit,
                                 String collection, double contribution) {
        if (hit == null || hit.id() == null) {
            return;
        }
        FusedHit current = fused.get(hit.id());
        if (current == null) {
            fused.put(hit.id(), new FusedHit(collection, hit.payload(), hit.score(), contribution));
        } else {
            current.add(contribution);
        }
    }

    private static Map<String, Object> toSource(String collection, Map<String, Object> payload, double score) {
        Map<String, Object> source = new LinkedHashMap<>();
        Object title = payload.get("title");
        if (title == null) {
            title = payload.get("source");
        }
        source.put("title", title == null ? "Källa" : title);
        source.put("text", payload.getOrDefault("text", ""));
        source.put("score", score);
        source.put("collection", collection);
        copy(payload, source, "documentId");
        copy(payload, source, "source");
        copy(payload, source, "page");
        copy(payload, source, "chunk");
        copy(payload, source, "chunkIndex");
        return source;
    }

    private static final class FusedHit {
        private final String collection;
        private final Map<String, Object> payload;
        private final double rawScore;
        private double rrf;

        private FusedHit(String collection, Map<String, Object> payload, double rawScore, double rrf) {
            this.collection = collection;
            this.payload = payload;
            this.rawScore = rawScore;
            this.rrf = rrf;
        }

        void add(double contribution) {
            rrf += contribution;
        }

        String collection() {
            return collection;
        }

        Map<String, Object> payload() {
            return payload;
        }

        double rawScore() {
            return rawScore;
        }

        double rrf() {
            return rrf;
        }
    }

    private static void copy(Map<String, Object> payload, Map<String, Object> target, String key) {
        if (payload.containsKey(key) && payload.get(key) != null) {
            target.put(key, payload.get(key));
        }
    }

    private List<String> resolveCollections(List<String> collections) {
        List<String> result = new ArrayList<>();
        if (collections != null) {
            for (String collection : collections) {
                if (collection != null && !collection.isBlank() && !result.contains(collection)) {
                    result.add(collection);
                }
            }
        }
        if (result.isEmpty()) {
            result.add(qdrant.collection());
        }
        return result;
    }

    private static String dedupKey(Map<String, Object> source) {
        String path = strOrNull(source.get("path"));
        String src = strOrNull(source.get("source"));
        String page = strOrNull(source.get("page"));
        String chunk = strOrNull(source.get("chunk"));
        if (chunk == null) {
            chunk = strOrNull(source.get("chunkIndex"));
        }
        if (path != null && page != null && chunk != null) {
            return "p:" + path + "|pg:" + page + "|c:" + chunk;
        }
        if (src != null && page != null && chunk != null) {
            return "s:" + src + "|pg:" + page + "|c:" + chunk;
        }
        if (path != null) {
            return "path:" + path;
        }
        if (src != null) {
            return "src:" + src;
        }
        Object textObj = source.get("text");
        String text = textObj == null ? "" : String.valueOf(textObj);
        return "txt:" + text.length() + ":" + text.hashCode();
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private List<Map<String, Object>> expandNeighbors(List<Map<String, Object>> sources, List<String> targets, int neighborChunks) {
        if (sources == null || sources.isEmpty() || neighborChunks <= 0) {
            return sources == null ? List.of() : sources;
        }
        int hitsIn = sources.size();
        try {
            // Track original order
            Map<String, List<IndexedHit>> groups = new LinkedHashMap<>();
            for (int idx = 0; idx < sources.size(); idx++) {
                Map<String, Object> source = sources.get(idx);
                Object documentId = source.get("documentId");
                if (documentId == null) {
                    String key = "__nogroup__" + idx;
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new IndexedHit(idx, source, true));
                    continue;
                }
                String collection = source.get("collection") == null ? (targets.isEmpty() ? qdrant.collection() : targets.get(0)) : String.valueOf(source.get("collection"));
                String key = collection + "|" + String.valueOf(documentId);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new IndexedHit(idx, source, false));
            }

            Map<String, List<MergedEntry>> mergedByGroup = new LinkedHashMap<>();
            for (Map.Entry<String, List<IndexedHit>> entry : groups.entrySet()) {
                String key = entry.getKey();
                List<IndexedHit> hits = entry.getValue();
                if (hits.isEmpty()) {
                    continue;
                }
                if (hits.get(0).ungrouped) {
                    List<MergedEntry> list = new ArrayList<>();
                    for (IndexedHit h : hits) {
                        list.add(new MergedEntry(h.originalIndex, h.source, new LinkedHashMap<>(h.source)));
                    }
                    mergedByGroup.put(key, list);
                    continue;
                }
                String collection = hits.get(0).source.get("collection") == null ? (targets.isEmpty() ? qdrant.collection() : targets.get(0)) : String.valueOf(hits.get(0).source.get("collection"));
                String documentId = String.valueOf(hits.get(0).source.get("documentId"));

                // Build all needed chunks across all hits in this group
                Set<Integer> neededAll = new HashSet<>();
                for (IndexedHit h : hits) {
                    Integer ci = asInt(h.source.get("chunkIndex"));
                    if (ci != null) {
                        for (int i = ci - neighborChunks; i <= ci + neighborChunks; i++) {
                            if (i >= 0) {
                                neededAll.add(i);
                            }
                        }
                    }
                }

                Map<Integer, Map<String, Object>> byIndex = new HashMap<>();
                if (!neededAll.isEmpty()) {
                    try {
                        Map<String, Object> filter = new LinkedHashMap<>();
                        int gte = Collections.min(neededAll);
                        int lte = Collections.max(neededAll);
                        filter.put("must", List.of(
                                Map.of("key", "documentId", "match", Map.of("value", documentId)),
                                Map.of("key", "chunkIndex", "range", Map.of("gte", gte, "lte", lte))
                        ));
                        List<Map<String, Object>> payloads = qdrantClient.scrollPayloads(collection,
                                List.of("text", "page", "chunkIndex", "title", "source", "documentId", "collection"),
                                filter);
                        for (Map<String, Object> p : payloads) {
                            Integer ci = asInt(p.get("chunkIndex"));
                            if (ci != null && neededAll.contains(ci)) {
                                byIndex.put(ci, p);
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to scroll neighbors for document {}", documentId, ex);
                        List<MergedEntry> list = new ArrayList<>();
                        for (IndexedHit h : hits) {
                            list.add(new MergedEntry(h.originalIndex, h.source, new LinkedHashMap<>(h.source)));
                        }
                        mergedByGroup.put(key, list);
                        continue;
                    }
                } else {
                    // no chunkIndex info anywhere - keep all originals
                    List<MergedEntry> list = new ArrayList<>();
                    for (IndexedHit h : hits) {
                        list.add(new MergedEntry(h.originalIndex, h.source, new LinkedHashMap<>(h.source)));
                    }
                    mergedByGroup.put(key, list);
                    continue;
                }

                if (byIndex.isEmpty()) {
                    List<MergedEntry> list = new ArrayList<>();
                    for (IndexedHit h : hits) {
                        list.add(new MergedEntry(h.originalIndex, h.source, new LinkedHashMap<>(h.source)));
                    }
                    mergedByGroup.put(key, list);
                    continue;
                }

                List<GroupedHit> groupedHits = new ArrayList<>();
                for (IndexedHit h : hits) {
                    GroupedHit gh = new GroupedHit(collection, documentId, h.source);
                    gh.originalIndex = h.originalIndex;
                    Integer ci = asInt(h.source.get("chunkIndex"));
                    if (ci == null) {
                        gh.expandedRange = null;
                        groupedHits.add(gh);
                        continue;
                    }
                    int start = ci - neighborChunks;
                    int end = ci + neighborChunks;
                    if (start < 0) {
                        start = 0;
                    }
                    List<Integer> range = new ArrayList<>();
                    for (int i = start; i <= end; i++) {
                        if (byIndex.containsKey(i)) {
                            range.add(i);
                        }
                    }
                    if (range.isEmpty()) {
                        gh.expandedRange = null;
                    } else {
                        gh.expandedRange = range;
                        gh.centerIndex = ci;
                    }
                    groupedHits.add(gh);
                }

                List<MergedWindow> merged = mergeWindows(groupedHits);
                List<MergedEntry> list = new ArrayList<>();
                for (MergedWindow mw : merged) {
                    if (mw == null || mw.bestHit == null) {
                        continue;
                    }
                    Map<String, Object> expandedSource = buildExpandedSource(mw, byIndex, mw.bestHit);
                    int bestPos = mw.bestHit.originalIndex;
                    list.add(new MergedEntry(bestPos, mw.bestHit.source, expandedSource));
                }
                // If merge dropped some hits (should not happen), add them back
                // build set of original indices covered
                java.util.Set<Integer> covered = new HashSet<>();
                for (MergedEntry me : list) {
                    covered.add(me.bestOriginalIndex);
                }
                for (GroupedHit gh : groupedHits) {
                    if (!covered.contains(gh.originalIndex)) {
                        // keep original
                        list.add(new MergedEntry(gh.originalIndex, gh.source, new LinkedHashMap<>(gh.source)));
                        covered.add(gh.originalIndex);
                    }
                }
                mergedByGroup.put(key, list);
            }

            List<MergedEntry> all = new ArrayList<>();
            for (List<MergedEntry> lst : mergedByGroup.values()) {
                all.addAll(lst);
            }
            all.sort(Comparator.comparingInt(e -> e.bestOriginalIndex));
            List<Map<String, Object>> result = new ArrayList<>();
            for (MergedEntry e : all) {
                result.add(e.expandedSource);
            }
            int windowsOut = result.size();
            int dropped = hitsIn - windowsOut;
            if (dropped < 0) {
                dropped = 0;
            }
            log.info("neighbor expansion: hits_in={} windows_out={} unchanged={} dropped={}", hitsIn, windowsOut, 0, dropped);
            if (dropped != 0) {
                return sources;
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to expand neighbors, falling back to original hits", e);
            return sources;
        }
    }

    private List<MergedWindow> mergeWindows(List<GroupedHit> hits) {
        List<Window> windows = new ArrayList<>();
        for (GroupedHit h : hits) {
            if (h.expandedRange == null || h.expandedRange.isEmpty()) {
                // keep original hit as a window of itself? no, but we need to not drop - caller will add back
                continue;
            }
            int s = h.expandedRange.get(0);
            int e = h.expandedRange.get(h.expandedRange.size() - 1);
            windows.add(new Window(s, e, h));
        }
        if (windows.isEmpty()) {
            return List.of();
        }
        windows.sort(Comparator.comparingInt(w -> w.start));
        List<MergedWindow> mergedList = new ArrayList<>();
        Window current = windows.get(0);
        for (int i = 1; i < windows.size(); i++) {
            Window next = windows.get(i);
            if (next.start <= current.end + 1) {
                current = new Window(Math.min(current.start, next.start), Math.max(current.end, next.end),
                        current.bestHit == null ? next.bestHit : (getScore(current.bestHit) >= getScore(next.bestHit) ? current.bestHit : next.bestHit));
            } else {
                mergedList.add(toMerged(current));
                current = next;
            }
        }
        mergedList.add(toMerged(current));
        return mergedList;
    }

    private double getScore(GroupedHit h) {
        Object s = h.source.get("score");
        if (s instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private MergedWindow toMerged(Window w) {
        return new MergedWindow(w.start, w.end, w.bestHit);
    }

    private Map<String, Object> buildExpandedSource(MergedWindow mw, Map<Integer, Map<String, Object>> byIndex, GroupedHit firstHit) {
        Map<String, Object> expanded = new LinkedHashMap<>(mw.bestHit.source);
        // collect chunks from start to end
        List<Integer> indices = new ArrayList<>();
        for (int i = mw.start; i <= mw.end; i++) {
            if (byIndex.containsKey(i)) {
                indices.add(i);
            }
        }
        indices.sort(Integer::compareTo);
        if (indices.isEmpty()) {
            return expanded;
        }
        // stitch
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < indices.size(); j++) {
            Map<String, Object> p = byIndex.get(indices.get(j));
            String t = p.get("text") == null ? "" : String.valueOf(p.get("text"));
            if (j == 0) {
                sb.append(t);
            } else {
                // try to remove overlap with previous chunk text
                String prev = byIndex.get(indices.get(j - 1)).get("text") == null ? "" : String.valueOf(byIndex.get(indices.get(j - 1)).get("text"));
                int overlap = findOverlap(prev, t, effective.integer("rag.chunk-overlap"));
                if (overlap > 0 && overlap < t.length()) {
                    sb.append(t.substring(overlap));
                } else if (overlap == 0) {
                    sb.append('\n').append(t);
                } else {
                    // overlap >= length, skip
                }
            }
        }
        expanded.put("text", sb.toString());
        // pages
        Integer pStart = null;
        Integer pEnd = null;
        Object hitPage = expanded.get("page");
        for (Integer idx : indices) {
            Map<String, Object> p = byIndex.get(idx);
            Object pg = p.get("page");
            if (pg instanceof Number n) {
                int pv = n.intValue();
                if (pStart == null || pv < pStart) {
                    pStart = pv;
                }
                if (pEnd == null || pv > pEnd) {
                    pEnd = pv;
                }
            }
        }
        if (pStart != null) {
            expanded.put("pageStart", pStart);
        }
        if (pEnd != null) {
            expanded.put("pageEnd", pEnd);
        }
        if (pStart != null && pEnd != null && pStart.equals(pEnd) && hitPage == null) {
            expanded.put("page", pStart);
        }
        // chunk indexes
        expanded.put("chunkIndexes", indices);
        expanded.put("expanded", true);
        // keep highest score/best rank position - we've already put bestHit.source which has original score; keep it
        return expanded;
    }

    private int findOverlap(String prev, String curr, int maxOverlap) {
        if (maxOverlap <= 0) {
            return 0;
        }
        int limit = Math.min(maxOverlap, Math.min(prev.length(), curr.length()));
        for (int len = limit; len >= 20; len--) {
            if (prev.regionMatches(prev.length() - len, curr, 0, len)) {
                return len;
            }
        }
        return 0;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            String s = String.valueOf(value);
            if (s.isBlank()) {
                return null;
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class IndexedHit {
        int originalIndex;
        Map<String, Object> source;
        boolean ungrouped;
        IndexedHit(int originalIndex, Map<String, Object> source, boolean ungrouped) {
            this.originalIndex = originalIndex;
            this.source = source;
            this.ungrouped = ungrouped;
        }
    }

    private static final class MergedEntry {
        int bestOriginalIndex;
        Map<String, Object> originalSource;
        Map<String, Object> expandedSource;
        MergedEntry(int bestOriginalIndex, Map<String, Object> originalSource, Map<String, Object> expandedSource) {
            this.bestOriginalIndex = bestOriginalIndex;
            this.originalSource = originalSource;
            this.expandedSource = expandedSource;
        }
    }

    private static final class GroupedHit {
        String collection;
        String documentId;
        Map<String, Object> source;
        List<Integer> expandedRange;
        int centerIndex;
        int originalIndex;

        GroupedHit(String collection, String documentId, Map<String, Object> source) {
            this.collection = collection;
            this.documentId = documentId;
            this.source = source;
        }
    }

    private static final class Window {
        int start;
        int end;
        GroupedHit bestHit;
        Window(int start, int end, GroupedHit bestHit) {
            this.start = start;
            this.end = end;
            this.bestHit = bestHit;
        }
    }

    private static final class MergedWindow {
        int start;
        int end;
        GroupedHit bestHit;
        MergedWindow(int start, int end, GroupedHit bestHit) {
            this.start = start;
            this.end = end;
            this.bestHit = bestHit;
        }
    }
}
