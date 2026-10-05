package dev.drytis.qolab.devupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * S6/S7: APK validation — wrong package, same/older version, non-APK bytes.
 *
 * Builds minimal but structurally-valid binary AndroidManifest.xml files
 * (string pool + start-element with the package / versionCode attributes) so
 * the validator's real parsing path is exercised.
 */
public class DevApkValidatorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PKG = "dev.drytis.qolab";

    // ---- minimal binary AXML builder ---------------------------------

    /** Builds a binary AndroidManifest.xml with packageName and versionCode. */
    private static byte[] axml(String pkg, int versionCode) {
        String[] strings = {pkg, "manifest", "versionCode"};
        // string pool chunk (UTF-16)
        int[] offsets = new int[strings.length];
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            String s = strings[i];
            // UTF-16 length: single 16-bit unit (little endian)
            data.write(s.length() & 0xFF);
            data.write((s.length() >> 8) & 0xFF);
            for (int j = 0; j < s.length(); j++) {
                char c = s.charAt(j);
                data.write(c & 0xFF);
                data.write((c >> 8) & 0xFF);
            }
            data.write(0); data.write(0); // UTF-16 null terminator
        }
        while (data.size() % 4 != 0) data.write(0);

        int stringsStart = 28 + strings.length * 4; // header 28 bytes + offsets
        int poolSize = stringsStart + data.size();
        byte[] pool = new byte[poolSize];
        le32(pool, 0, 0x0001);       // type RES_STRING_POOL_TYPE
        le32(pool, 4, poolSize);     // chunk size
        le32(pool, 8, strings.length);
        le32(pool, 12, 0);           // styleCount
        le32(pool, 16, 0x00000100);  // flags: UTF-16? 0x100 = UTF8 flag NOT set... keep 0
        le32(pool, 16, 0);           // flags
        le32(pool, 20, stringsStart);
        le32(pool, 24, 0);           // stylesStart
        for (int i = 0; i < strings.length; i++) le32(pool, 28 + i * 4, offsets[i]);
        System.arraycopy(data.toByteArray(), 0, pool, stringsStart, data.size());

        // start-element chunk: header(8) + lineNumber(4) + comment(4)
        // + attrExt{ns(4) name(4) attrStart(2) attrSize(2) attrCount(2) idIdx(2) classIdx(2) styleIdx(2)}
        // attributeStart is relative to the attrExt struct start (chunkStart+16)
        int attrCount = 2;
        int attrStartOff = 8 + 4 + 4; // within attrExt → attrs begin at chunkStart+16+attrStartOff
        int startSize = 16 + attrStartOff + attrCount * 20;
        byte[] start = new byte[startSize];
        le32(start, 0, 0x0102);      // RES_XML_START_ELEMENT_TYPE
        le32(start, 4, startSize);
        le32(start, 8, 1);           // lineNumber
        le32(start, 12, -1);         // comment
        // attrExt at 16:
        le32(start, 16, -1);         // ns
        le32(start, 20, 1);          // name → "manifest"
        le16(start, 24, attrStartOff);
        le16(start, 26, 20);         // attrSize
        le16(start, 28, attrCount);
        le16(start, 30, 0); le16(start, 32, 0); le16(start, 34, 0);
        // attrs begin at chunkStart + 16 + attrStartOff
        int a0 = 16 + attrStartOff;
        le32(start, a0, -1);           // ns
        le32(start, a0 + 4, 0);        // name idx (unused without resource map)
        le32(start, a0 + 8, 0);        // rawValue → strings[0] = pkg
        start[a0 + 15] = 0x03;         // STRING
        le32(start, a0 + 16, 0);
        // attr 1: versionCode (int)
        int a1 = 16 + attrStartOff + 20;
        le32(start, a1, -1);
        le32(start, a1 + 4, 1);
        le32(start, a1 + 8, -1);
        start[a1 + 15] = 0x10;         // INT_DEC
        le32(start, a1 + 16, versionCode);

        // resource map chunk (RES_XML_RESOURCE_MAP_TYPE): string idx → attr id
        int[] mapIds = {0x01010003, 0x0101021b};
        byte[] resMap = new byte[8 + mapIds.length * 4];
        le32(resMap, 0, 0x0104);
        le32(resMap, 4, resMap.length);
        for (int i = 0; i < mapIds.length; i++) le32(resMap, 8 + i * 4, mapIds[i]);

        // file header (XML chunk): type 0x0003, size covers everything
        int total = 8 + resMap.length + pool.length + start.length;
        byte[] file = new byte[total];
        le32(file, 0, 0x0003);
        le32(file, 4, total);
        System.arraycopy(resMap, 0, file, 8, resMap.length);
        System.arraycopy(pool, 0, file, 8 + resMap.length, pool.length);
        System.arraycopy(start, 0, file, 8 + resMap.length + pool.length, start.length);
        return file;
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void le16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private File apk(String pkg, int versionCode) throws Exception {
        File f = tmp.newFile("test-" + System.nanoTime() + ".apk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            z.write(axml(pkg, versionCode));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("classes.dex"));
            z.write(new byte[2048]);
            z.closeEntry();
        }
        return f;
    }

    @Test
    public void validNewerApkPasses() throws Exception {
        DevApkValidator.Result r = DevApkValidator.validate(
                apk(PKG, 42).getAbsolutePath(), PKG, 40);
        assertTrue(r.reason, r.ok);
        assertEquals(PKG, r.packageName);
    }

    @Test
    public void wrongPackageRejected() throws Exception {
        DevApkValidator.Result r = DevApkValidator.validate(
                apk("com.other.app", 42).getAbsolutePath(), PKG, 40);
        assertFalse(r.ok);
        assertTrue(r.reason, r.reason.contains("package mismatch"));
    }

    @Test
    public void sameVersionRejected() throws Exception {
        DevApkValidator.Result r = DevApkValidator.validate(
                apk(PKG, 40).getAbsolutePath(), PKG, 40);
        assertFalse(r.ok);
        assertTrue(r.reason, r.reason.contains("not newer"));
    }

    @Test
    public void olderVersionRejected() throws Exception {
        DevApkValidator.Result r = DevApkValidator.validate(
                apk(PKG, 39).getAbsolutePath(), PKG, 40);
        assertFalse(r.ok);
        assertTrue(r.reason, r.reason.contains("not newer"));
    }

    @Test
    public void nonApkBytesRejected() throws Exception {
        File f = tmp.newFile("garbage.apk");
        try (OutputStream o = new FileOutputStream(f)) {
            for (byte[] chunk = new byte[4096]; f.length() < 8192; ) {
                o.write(chunk);
            }
            o.write("definitely not a zip file".getBytes(StandardCharsets.UTF_8));
        }
        DevApkValidator.Result r = DevApkValidator.validate(f.getAbsolutePath(), PKG, 40);
        assertFalse(r.ok);
    }

    @Test
    public void zipWithoutManifestRejected() throws Exception {
        File f = tmp.newFile("empty.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("hello.txt"));
            z.write(new byte[4096]);
            z.closeEntry();
        }
        DevApkValidator.Result r = DevApkValidator.validate(f.getAbsolutePath(), PKG, 40);
        assertFalse(r.ok);
        assertTrue(r.reason, r.reason.contains("not an APK"));
    }

    @Test
    public void missingFileRejected() {
        DevApkValidator.Result r = DevApkValidator.validate(
                new File(tmp.getRoot(), "nope.apk").getAbsolutePath(), PKG, 40);
        assertFalse(r.ok);
    }
}
