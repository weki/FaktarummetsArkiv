package se.rightech.fakturummetsarkiv.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceSettings(
        String chatMode,
        String chatModel,
        String validatorModel,
        Boolean validateGrounding,
        Boolean smartSearch,
        Boolean rewriteQuery,
        List<String> collections,
        Double similarityThreshold,
        Integer topN,
        Double temperature,
        Integer historySize,
        List<String> documentIds,
        String embeddingModel,
        String refineModel,
        String gateModel,
        String rewriteModel) {

    /** Bakåtkompatibel konstruktor utan de separata modellvalen. */
    public WorkspaceSettings(String chatMode, String chatModel, String validatorModel,
                             Boolean validateGrounding, Boolean smartSearch, Boolean rewriteQuery,
                             List<String> collections, Double similarityThreshold, Integer topN,
                             Double temperature, Integer historySize, List<String> documentIds) {
        this(chatMode, chatModel, validatorModel, validateGrounding, smartSearch, rewriteQuery,
                collections, similarityThreshold, topN, temperature, historySize, documentIds,
                null, null, null, null);
    }
}
