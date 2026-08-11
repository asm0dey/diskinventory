package com.kodewerk.diskinventory.model;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Reads allocated on-disk size (st_blocks * 512, what {@code du} reports) via
 * an FFM {@code lstat} downcall — the JDK's attribute views do not expose
 * block counts. One instance per scan; confined to the scanning thread.
 * If the platform is unsupported or any call fails, callers fall back to the
 * logical size.
 */
final class AllocatedSizeProbe implements AutoCloseable {

    private static final int STAT_BUF_BYTES = 256;
    private static final int PATH_BUF_BYTES = 8192;
    private static final long DEV_BSIZE = 512;

    private final Arena arena;
    private final MethodHandle lstat;
    private final MemorySegment statBuf;
    private final MemorySegment pathBuf;
    private final long blocksOffset;

    private AllocatedSizeProbe(Arena arena, MethodHandle lstat, long blocksOffset) {
        this.arena = arena;
        this.lstat = lstat;
        this.blocksOffset = blocksOffset;
        this.statBuf = arena.allocate(STAT_BUF_BYTES);
        this.pathBuf = arena.allocate(PATH_BUF_BYTES);
    }

    /** Returns a probe for this platform, or null if unsupported. */
    static AllocatedSizeProbe create() {
        long offset = blocksOffsetForOs();
        if (offset < 0) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            // On x86_64 darwin the plain "lstat" symbol uses the legacy
            // 32-bit-inode struct layout; the modern layout (blocks @104) is
            // "lstat$INODE64". arm64 has no $INODE64 variants - its "lstat"
            // is already the modern struct. Prefer $INODE64, fall back.
            MemorySegment symbol = linker.defaultLookup().find("lstat$INODE64")
                    .or(() -> linker.defaultLookup().find("lstat"))
                    .orElseThrow();
            MethodHandle handle = linker.downcallHandle(symbol,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            return new AllocatedSizeProbe(Arena.ofConfined(), handle, offset);
        } catch (Throwable t) {
            // Native images throw MissingForeignRegistrationError (an Error, not a
            // RuntimeException) when the lstat downcall stub wasn't registered at
            // build time, so this must catch Throwable to degrade instead of crashing.
            return null;
        }
    }

    private static long blocksOffsetForOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return 104;     // darwin 64-bit struct stat: st_size @96, st_blocks @104
        }
        if (os.contains("linux")) {
            return 64;      // linux x86_64/aarch64 struct stat: st_size @48, st_blocks @64
        }
        return -1;
    }

    /** Allocated bytes of the file at {@code path}, or {@code fallback} on any failure. */
    long allocatedOf(Path path, long fallback) {
        try {
            String p = path.toString();
            if (p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= PATH_BUF_BYTES) {
                return fallback;
            }
            pathBuf.setString(0, p);
            int rc = (int) lstat.invokeExact(pathBuf, statBuf);
            if (rc != 0) {
                return fallback;
            }
            return statBuf.get(JAVA_LONG, blocksOffset) * DEV_BSIZE;
        } catch (Throwable t) {
            return fallback;
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
