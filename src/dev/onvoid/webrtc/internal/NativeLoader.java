package dev.onvoid.webrtc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replacement for webrtc-java's NativeLoader — the original uses
 * ClassLoader.getResourceAsStream which returns null in Java 17+ when the
 * fat-JAR contains a module-info.class from a named module.
 * This version tries four progressively broader resource-lookup strategies.
 */
public class NativeLoader {

    private static final Set<String> LOADED_LIB_SET = ConcurrentHashMap.newKeySet();

    public static void loadLibrary(String name) throws Exception {
        if (LOADED_LIB_SET.contains(name)) return;

        String libFile = System.mapLibraryName(name); // "webrtc-java.dll" on Windows
        String base    = stripExt(libFile);
        String ext     = fileExt(libFile);

        Path tmp = Files.createTempFile(base, ext);
        java.io.File tmpFile = tmp.toFile();
        try {
            InputStream is = openResource(libFile);
            if (is == null) {
                tmpFile.delete();
                throw new IOException("Native library resource not found: " + libFile);
            }
            try (InputStream src = is) {
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            System.load(tmp.toAbsolutePath().toString());
            LOADED_LIB_SET.add(name);
        } finally {
            boolean posix = FileSystems.getDefault()
                    .supportedFileAttributeViews().contains("posix");
            if (posix) {
                tmpFile.delete();
            } else {
                tmpFile.deleteOnExit();
            }
        }
    }

    private static InputStream openResource(String name) {
        // 1. Class.getResourceAsStream — strips leading '/', works in all Java versions
        InputStream is = NativeLoader.class.getResourceAsStream("/" + name);
        if (is != null) return is;

        // 2. Same classloader, no leading slash (original webrtc-java approach)
        ClassLoader cl = NativeLoader.class.getClassLoader();
        if (cl != null) {
            is = cl.getResourceAsStream(name);
            if (is != null) return is;
        }

        // 3. System classloader
        is = ClassLoader.getSystemResourceAsStream(name);
        if (is != null) return is;

        // 4. Thread context classloader
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            is = ctx.getResourceAsStream(name);
        }
        return is;
    }

    private static String stripExt(String name) {
        String n = name.replace('\\', '/');
        int dot   = n.lastIndexOf('.');
        int slash = n.lastIndexOf('/');
        return (dot > slash && dot >= 0) ? name.substring(0, dot) : name;
    }

    private static String fileExt(String name) {
        String n = name.replace('\\', '/');
        int dot   = n.lastIndexOf('.');
        int slash = n.lastIndexOf('/');
        return (dot > slash && dot >= 0) ? name.substring(dot) : "";
    }
}
