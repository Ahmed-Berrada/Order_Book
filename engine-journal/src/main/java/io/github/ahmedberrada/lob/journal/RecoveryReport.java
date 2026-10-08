package io.github.ahmedberrada.lob.journal;

/**
 * What {@link JournaledEngine#open} did to rebuild the engine.
 *
 * @param snapshotSequence      command sequence of the snapshot used, or 0 for a full replay
 * @param commandsReplayed      commands applied after the snapshot
 * @param lastCommandSequence   last command in the journal; the next one gets this plus 1
 * @param eventBatchesRestored  event batches regenerated because the event journal was behind
 * @param tornBytesDiscarded    bytes of incomplete records cut from the end of the journals (RS-003)
 */
public record RecoveryReport(
        long snapshotSequence,
        long commandsReplayed,
        long lastCommandSequence,
        long eventBatchesRestored,
        long tornBytesDiscarded) {
}
