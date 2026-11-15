package io.github.ahmedberrada.lob.journal;

/** When the command journal is flushed to the storage device (rulebook RS-001). */
public enum FsyncPolicy {
    /** Before every acknowledgement. Survives power loss; costs one device flush per command. */
    EVERY_COMMAND,
    /** Left to the operating system. Survives a process crash, not a machine crash. */
    OS
}
