package io.github.ahmedberrada.lob.journal;

/** Test clock: advances by one microsecond per reading unless set explicitly. */
final class ManualClock implements TimeSource {

    private long nowMicros;

    ManualClock(long startMicros) {
        this.nowMicros = startMicros;
    }

    void set(long micros) {
        this.nowMicros = micros;
    }

    @Override
    public long nowMicros() {
        return nowMicros++;
    }
}
