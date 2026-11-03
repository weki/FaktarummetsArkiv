package se.rightech.fakturummetsarkiv.video;

/**
 * Ett analyserat tidssegment i en video.
 *
 * @param startSecond första sekunden i segmentet
 * @param endSecond   sista sekunden i segmentet
 * @param text        beskrivning av vad som händer under segmentet
 */
public record VideoSegment(int startSecond, int endSecond, String text) {
}
