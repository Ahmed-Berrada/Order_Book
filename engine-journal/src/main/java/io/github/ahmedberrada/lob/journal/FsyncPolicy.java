package io.github.ahmedberrada.lob.journal;

/** When the command journal is flushed to the storage device (rulebook RS-001). */
public enum FsyncPolicy {
    /** Before every acknowledgement. Survives power loss; costs one device flush per command. */
    EVERY_COMMAND,
    /**
     * Before the acknowledgement of a group of commands: the caller processes the commands waiting together,
     * then calls {@link JournaledEngine#sync()} once before acknowledging any of them (ADR-0020). Survives
     * power loss; costs one device flush per group.
     */
    GROUP,
    /** Left to the operating system. Survives a process crash, not a machine crash. */
    OS
}
