package dev.drytis.qolab.devupdate;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure-JVM APK validation for the Development updater.
 *
 * Security checks before the installer is EVER launched:
 *  1. the file is a valid zip (central directory readable via {@link ZipFile});
 *  2. AndroidManifest.xml is present;
 *  3. packageName parsed from the binary AndroidManifest.xml matches the
 *     installed application id (wrong-package APKs are rejected);
 *  4. versionCode parsed from the manifest is GREATER than the installed one.
 *
 * The signature check against the installed app is performed by the controller
 * via PackageManager (needs the platform); this class keeps everything that
 * does not need Android on the JVM so it is unit-testable.
 */
public final class DevApkValidator {

    public static final class Result {
        public final boolean ok;
        public final String packageName;
        public final long versionCode;
        public final String reason;   // human-readable failure reason, null when ok

        private Result(boolean ok, String pkg, long code, String reason) {
            this.ok = ok;
            this.packageName = pkg;
            this.versionCode = code;
            this.reason = reason;
        }

        static Result fail(String reason) {
            return new Result(false, null, 0, reason);
        }
    }

    private DevApkValidator() {
    }

    /**
     * @param path                downloaded file path
     * @param expectedPackage     installed application id (dev.drytis.qolab)
     * @param installedVersionCode currently installed versionCode
     */
    public static Result validate(String path, String expectedPackage, long installedVersionCode) {
        if (path == null) return Result.fail("no file");
        java.io.File f = new java.io.File(path);
        if (!f.exists() || f.length() < 64) return Result.fail("file missing or too small");

        try (ZipFile zip = new ZipFile(f)) {
            ZipEntry manifestEntry = zip.getEntry("AndroidManifest.xml");
            if (manifestEntry == null) return Result.fail("not an APK (no AndroidManifest.xml)");

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            byte[] axml = buf.toByteArray();
            if (axml.length < 8) return Result.fail("manifest too small");

            String pkg = parsePackageName(axml);
            if (pkg == null) return Result.fail("package name unreadable");
            if (!pkg.equals(expectedPackage)) return Result.fail("package mismatch: " + pkg);

            long code = parseVersionCode(axml);
            if (code <= 0) return Result.fail("versionCode unreadable");
            if (code <= installedVersionCode) return Result.fail("version not newer: " + code);

            return new Result(true, pkg, code, null);
        } catch (IOException e) {
            return Result.fail("not a valid zip/apk: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Minimal AXML parsing — only what the two attribute checks need.
    // Binary AndroidManifest.xml: string pool then chunks with attribute
    // offsets. We scan the string pool for the package/versionCode names and
    // the resource-id chunk for the two well-known attribute ids.
    // ------------------------------------------------------------------

    private static final int ATTR_PACKAGE_ID = 0x01010003;
    private static final int ATTR_VERSIONCODE_ID = 0x0101021b;

    private static String parsePackageName(byte[] axml) {
        Object v = findAttribute(axml, ATTR_PACKAGE_ID);
        return v instanceof String ? (String) v : null;
    }

    private static long parseVersionCode(byte[] axml) {
        Object v = findAttribute(axml, ATTR_VERSIONCODE_ID);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return (Integer) v;
        return -1;
    }

    /**
     * Scans the FIRST start-element chunk of the binary XML (the &lt;manifest&gt;
     * element) for the attribute with the given resource id. Attribute ids come
     * from the RES_XML_RESOURCE_MAP chunk, indexed by the attribute NAME's
     * string-pool index.
     *
     * Start-element chunk layout:
     *   header(8) lineNumber(4) comment(4) | attrExt: ns(4) name(4)
     *   attributeStart(2) attributeSize(2) attributeCount(2) ... | attributes
     * attributeStart is relative to the START of the attrExt struct
     * (chunkStart + 16), not to the chunk start.
     *
     * Attribute layout (ResXMLTree_attribute, 20 bytes):
     *   +0 ns(4) +4 name(4) +8 rawValue(4) +12 typedValue{size(2) res0(1) type(1)} +16 data(4)
     */
    private static Object findAttribute(byte[] axml, int attrResId) {
        int startOff = firstStartElement(axml);
        if (startOff < 0) return null;
        if (startOff + 16 + 12 > axml.length) return null;
        int attrStart = le16(axml, startOff + 24);
        int attrSize = le16(axml, startOff + 26);
        int attrCount = le16(axml, startOff + 28);
        if (attrSize < 20) attrSize = 20;
        int attrsOff = startOff + 16 + attrStart;
        int[] ids = resourceMap(axml, startOff);
        if (ids != null) {
            for (int i = 0; i < attrCount; i++) {
                int a = attrsOff + i * attrSize;
                if (a + 20 > axml.length) break;
                int nameIdx = le32(axml, a + 4);
                int resId = (nameIdx >= 0 && nameIdx < ids.length) ? ids[nameIdx] : -1;
                if (resId != attrResId) continue;
                return attributeValue(axml, a);
            }
            return null;
        }
        // No resource-map chunk (aapt2 omits it in this manifest): resolve by
        // the attribute NAME string ("package" / "versionCode") instead.
        String wantName = attrResId == ATTR_PACKAGE_ID ? "package" : "versionCode";
        for (int i = 0; i < attrCount; i++) {
            int a = attrsOff + i * attrSize;
            if (a + 20 > axml.length) break;
            int nameIdx = le32(axml, a + 4);
            if (nameIdx < 0) continue;
            String n = stringFromPool(axml, nameIdx);
            if (wantName.equals(n)) return attributeValue(axml, a);
        }
        return null;
    }

    /** Reads the typed value of the attribute at byte offset a. */
    private static Object attributeValue(byte[] axml, int a) {
        int rawValueIdx = le32(axml, a + 8);
        int valueType = axml[a + 15] & 0xFF;
        int data = le32(axml, a + 16);
        if (valueType == 0x03) { // STRING — rawValue index, else data
            int idx = rawValueIdx >= 0 ? rawValueIdx : data;
            return stringFromPool(axml, idx);
        }
        if (valueType == 0x10) { // INT_DEC
            return (long) data;
        }
        return null;
    }

    /** Finds the RES_XML_RESOURCE_MAP chunk preceding the start element. */
    private static int[] resourceMap(byte[] axml, int startOff) {
        int off = 8;
        while (off + 8 <= axml.length && off < startOff) {
            int type = le16(axml, off);
            int size = le32(axml, off + 4);
            if (size <= 0 || off + size > axml.length) break;
            if (type == 0x0104) { // RES_XML_RESOURCE_MAP_TYPE
                int count = (size - 8) / 4;
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) ids[i] = le32(axml, off + 8 + i * 4);
                return ids;
            }
            off += size;
        }
        return null;
    }

    private static int firstStartElement(byte[] axml) {
        int off = 8;
        while (off + 8 <= axml.length) {
            int type = le16(axml, off);
            int size = le32(axml, off + 4);
            if (size <= 0 || off + size > axml.length) break;
            if (type == 0x0102) return off;
            off += size;
        }
        return -1;
    }

    private static String stringFromPool(byte[] axml, int index) {
        try {
            int off = 8;
            while (off + 8 <= axml.length) {
                int type = le16(axml, off);
                int size = le32(axml, off + 4);
                if (size <= 0 || off + size > axml.length) break;
                if (type == 0x0001) { // RES_STRING_POOL_TYPE
                    int stringCount = le32(axml, off + 8);
                    int flags = le32(axml, off + 16);
                    boolean utf8 = (flags & 0x100) != 0;
                    if (index < 0 || index >= stringCount) return null;
                    int stringsStart = le32(axml, off + 20);
                    int strOff = off + stringsStart + le32(axml, off + 28 + index * 4);
                    if (utf8) {
                        // UTF-8 pool: charLen varint (skip), byteLen varint, bytes
                        int p = strOff;
                        p += varLen(axml, p);       // character count (ignored)
                        int byteLen = varLen(axml, p);
                        p += varLenSize(axml, p);
                        if (byteLen > 1024 || p + byteLen > axml.length) return null;
                        return new String(axml, p, byteLen, StandardCharsets.UTF_8);
                    }
                    // UTF-16 pool: 16-bit length unit (two units when the
                    // high bit of the first is set), then UTF-16LE bytes
                    int p = strOff;
                    int len = le16(axml, p);
                    p += 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7FFF) << 16) | le16(axml, p);
                        p += 2;
                    }
                    if (len > 1024 || p + len * 2 > axml.length) return null;
                    StringBuilder sb = new StringBuilder(len);
                    for (int i = 0; i < len; i++) {
                        sb.append((char) ((axml[p + i * 2] & 0xFF)
                                | ((axml[p + i * 2 + 1] & 0xFF) << 8)));
                    }
                    return sb.toString();
                }
                off += size;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** UTF-8 pool varint value. */
    private static int varLen(byte[] b, int off) {
        int v = b[off] & 0xFF;
        if ((v & 0x80) != 0) return ((v & 0x7F) << 8) | (b[off + 1] & 0xFF);
        return v;
    }

    /** Width in bytes of the varint at off. */
    private static int varLenSize(byte[] b, int off) {
        return (b[off] & 0x80) != 0 ? 2 : 1;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** UTF-8 helper kept for potential future use (string pool is UTF-16 here). */
    @SuppressWarnings("unused")
    private static String utf8(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static EOFException eof(String m) {
        return new EOFException(m);
    }
}
