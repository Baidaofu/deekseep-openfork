package com.dsmod.probe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal DEX string-pool reader.
 *
 * <p>The remote feature-flag manager needs every rollout key compiled into the installed DeepSeek
 * build, but the host MMKV only holds the subset its server has already pushed. The keys are plain
 * string constants, so reading the string-id table is enough; an 11 MB dex is walked in a few
 * milliseconds because only the id table is touched and non-matching entries are rejected on their
 * raw bytes before any String is allocated. That keeps this dependency free instead of pulling a
 * whole dex library into the module.</p>
 */
final class HostDexStrings {
    /** {@code file_size} in the DEX header. */
    private static final int OFF_FILE_SIZE = 0x20;
    /** {@code string_ids_size} in the DEX header. */
    private static final int OFF_STRING_IDS_SIZE = 0x38;
    /** {@code string_ids_off} in the DEX header. */
    private static final int OFF_STRING_IDS_OFF = 0x3C;
    private static final int MAX_STRING_IDS = 4000000;
    private static final int MAX_DEX_BYTES = 96 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 8192;

    private HostDexStrings() {}

    /** Every string in {@code apk}'s {@code classes*.dex} that starts with {@code prefix}. */
    static Set<String> stringsWithPrefix(File apk, String prefix) {
        if (apk == null || prefix == null || prefix.length() == 0 || !apk.isFile()) {
            return Collections.emptySet();
        }
        Set<String> found = new LinkedHashSet<String>();
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isDexEntry(entry.getName())) continue;
                InputStream stream = null;
                try {
                    stream = zip.getInputStream(entry);
                    byte[] dex = readAll(stream);
                    if (dex != null) collect(dex, prefix, found);
                } catch (Throwable ignored) {
                    // A single unreadable entry must not lose the other dex files.
                } finally {
                    closeQuietly(stream);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(zip);
        }
        return found;
    }

    /** Same as {@link #stringsWithPrefix(File, String)} over the host base APK and its splits. */
    static Set<String> stringsWithPrefix(String sourceDir, String[] splitSourceDirs, String prefix) {
        Set<String> found = new LinkedHashSet<String>();
        if (sourceDir != null) found.addAll(stringsWithPrefix(new File(sourceDir), prefix));
        if (splitSourceDirs != null) {
            for (String split : splitSourceDirs) {
                if (split != null) found.addAll(stringsWithPrefix(new File(split), prefix));
            }
        }
        return found;
    }

    private static void collect(byte[] dex, String prefix, Set<String> out) {
        if (dex.length < OFF_STRING_IDS_OFF + 4) return;
        if (dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') return;
        int fileSize = readInt(dex, OFF_FILE_SIZE);
        if (fileSize <= 0 || fileSize > dex.length) fileSize = dex.length;
        int count = readInt(dex, OFF_STRING_IDS_SIZE);
        int tableOff = readInt(dex, OFF_STRING_IDS_OFF);
        if (count <= 0 || count > MAX_STRING_IDS || tableOff <= 0) return;
        long tableEnd = (long) tableOff + (long) count * 4L;
        if (tableEnd > fileSize) return;

        boolean asciiPrefix = isAscii(prefix);
        char[] prefixChars = prefix.toCharArray();
        for (int index = 0; index < count; index++) {
            int dataOff = readInt(dex, tableOff + index * 4);
            if (dataOff <= 0 || dataOff >= fileSize) continue;
            // string_data_item: uleb128 utf16_size, then MUTF-8 bytes, then a NUL.
            int cursor = skipUleb128(dex, dataOff, fileSize);
            if (cursor < 0) continue;
            if (asciiPrefix && !matchesAsciiPrefix(dex, cursor, fileSize, prefixChars)) continue;
            String value = decodeMutf8(dex, cursor, fileSize);
            if (value != null && value.startsWith(prefix)) out.add(value);
        }
    }

    private static boolean isDexEntry(String name) {
        if (name == null || name.indexOf('/') >= 0) return false;
        return name.equals("classes.dex") || (name.startsWith("classes") && name.endsWith(".dex"));
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    /**
     * ASCII prefix bytes are identical in MUTF-8, so the raw comparison filters out virtually every
     * string without allocating anything.
     */
    private static boolean matchesAsciiPrefix(byte[] dex, int offset, int limit, char[] prefix) {
        if (offset < 0 || offset + prefix.length > limit) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((dex[offset + i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static String decodeMutf8(byte[] dex, int offset, int limit) {
        StringBuilder sb = new StringBuilder(32);
        int end = Math.min(limit, offset + MAX_STRING_BYTES);
        int i = offset;
        while (i < end) {
            int first = dex[i] & 0xFF;
            if (first == 0) break;
            if (first < 0x80) {
                sb.append((char) first);
                i++;
                continue;
            }
            if ((first & 0xE0) == 0xC0) {
                if (i + 1 >= end) return null;
                int second = dex[i + 1] & 0xFF;
                if ((second & 0xC0) != 0x80) return null;
                sb.append((char) (((first & 0x1F) << 6) | (second & 0x3F)));
                i += 2;
                continue;
            }
            if ((first & 0xF0) == 0xE0) {
                if (i + 2 >= end) return null;
                int second = dex[i + 1] & 0xFF;
                int third = dex[i + 2] & 0xFF;
                if ((second & 0xC0) != 0x80 || (third & 0xC0) != 0x80) return null;
                sb.append((char) (((first & 0x0F) << 12)
                        | ((second & 0x3F) << 6) | (third & 0x3F)));
                i += 3;
                continue;
            }
            // Not a MUTF-8 lead byte; keep scanning instead of losing the whole pool.
            sb.append((char) first);
            i++;
        }
        return sb.toString();
    }

    private static int skipUleb128(byte[] dex, int offset, int limit) {
        int i = offset;
        int consumed = 0;
        while (i < limit && consumed < 5) {
            int value = dex[i] & 0xFF;
            i++;
            consumed++;
            if ((value & 0x80) == 0) return i;
        }
        return -1;
    }

    private static int readInt(byte[] dex, int offset) {
        if (offset < 0 || offset + 4 > dex.length) return -1;
        return (dex[offset] & 0xFF)
                | ((dex[offset + 1] & 0xFF) << 8)
                | ((dex[offset + 2] & 0xFF) << 16)
                | ((dex[offset + 3] & 0xFF) << 24);
    }

    private static byte[] readAll(InputStream stream) throws java.io.IOException {
        if (stream == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buffer = new byte[1 << 16];
        int total = 0;
        while (true) {
            int read = stream.read(buffer);
            if (read < 0) break;
            if (read == 0) continue;
            total += read;
            if (total > MAX_DEX_BYTES) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(ZipFile zip) {
        if (zip == null) return;
        try { zip.close(); } catch (Throwable ignored) {}
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (Throwable ignored) {}
    }
}
