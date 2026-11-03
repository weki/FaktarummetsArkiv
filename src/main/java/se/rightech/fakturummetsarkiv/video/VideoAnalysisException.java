package se.rightech.fakturummetsarkiv.video;

/**
 * Kastas när en videoanalys misslyckas totalt. Mappas av controllers till 400/503.
 */
public class VideoAnalysisException extends RuntimeException {
    public VideoAnalysisException(String message) {
        super(message);
    }

    public VideoAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
