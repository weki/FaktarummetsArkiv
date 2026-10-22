package se.rightech.fakturummetsarkiv.rag;

import java.util.List;
import java.util.Map;

/** Omordnar hämtade kandidater efter relevans för frågan. */
public interface Reranker {

    List<Map<String, Object>> rerank(String query, List<Map<String, Object>> candidates, int topK);
}
