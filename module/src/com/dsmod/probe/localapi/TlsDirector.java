package com.dsmod.probe.localapi;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TlsDirector {
    private static final String CA_ALIAS = "deekseep-local-ca";
    private static final String CA_CERT = "dq0_ca.cer";
    private static final String CA_STORE = "dq0_ca.p12";
    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final Object LOCK = new Object();
    private static final String PASSWORD_FILE = "dq0_tls_password";
    private static final String SERVER_ALIAS = "dq0-tls";
    private static final String SERVER_STORE = "dq0_server.p12";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String SIGNATURE_OID = "1.2.840.113549.1.1.11";
    private static final long VALIDITY_MS = 315360000000L;
    private static final String VERIFIED = "dq0_tls_verified";

    private TlsDirector() {
    }

    public static final class Material {
        public final X509Certificate authorityCertificate;
        public final KeyStore authorityStore;
        public final char[] password;
        public final KeyStore serverStore;

        Material(KeyStore keyStore, KeyStore keyStore2, char[] cArr, X509Certificate x509Certificate) {
            this.authorityStore = keyStore;
            this.serverStore = keyStore2;
            this.password = cArr;
            this.authorityCertificate = x509Certificate;
        }
    }

    public static void prepare(Context context) throws Exception {
        PrivateKey privateKey;
        X509Certificate x509Certificate;
        boolean z;
        Context appContext = appContext(context);
        if (appContext == null) {
            throw new IllegalArgumentException("missing context");
        }
        synchronized (LOCK) {
            File filesDir = appContext.getFilesDir();
            char[] password = password(appContext);
            File file = new File(filesDir, CA_STORE);
            File file2 = new File(filesDir, CA_CERT);
            if (file.isFile() && file2.isFile()) {
                KeyStore load = load(file, password);
                x509Certificate = (X509Certificate) load.getCertificate(CA_ALIAS);
                privateKey = (PrivateKey) load.getKey(CA_ALIAS, password);
                if (privateKey == null || x509Certificate == null) {
                    throw new IOException("Local API CA keystore is incomplete");
                }
            } else {
                KeyPair generateKeyPair = generateKeyPair();
                X509Certificate selfSignedAuthority = selfSignedAuthority(generateKeyPair);
                KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
                keyStore.load(null, null);
                keyStore.setKeyEntry(CA_ALIAS, generateKeyPair.getPrivate(), password, new Certificate[]{selfSignedAuthority});
                writeStore(file, keyStore, password);
                LocalApiConfig.writeAtomic(file2, selfSignedAuthority.getEncoded());
                privateKey = generateKeyPair.getPrivate();
                x509Certificate = selfSignedAuthority;
            }
            File file3 = new File(filesDir, SERVER_STORE);
            if (!file3.isFile()) {
                z = true;
            } else {
                try {
                    X509Certificate x509Certificate2 = (X509Certificate) load(file3, password).getCertificate(SERVER_ALIAS);
                    x509Certificate2.verify(x509Certificate.getPublicKey());
                    z = !covers(x509Certificate2, subjectNames());
                } catch (Throwable th) {
                    z = true;
                }
            }
            if (z) {
                KeyPair generateKeyPair2 = generateKeyPair();
                X509Certificate issueLeaf = issueLeaf(x509Certificate, privateKey, generateKeyPair2.getPublic());
                KeyStore keyStore2 = KeyStore.getInstance(KEYSTORE_TYPE);
                keyStore2.load(null, null);
                keyStore2.setKeyEntry(SERVER_ALIAS, generateKeyPair2.getPrivate(), password, new Certificate[]{issueLeaf, x509Certificate});
                writeStore(file3, keyStore2, password);
                File file4 = new File(filesDir, VERIFIED);
                if (file4.exists()) {
                    file4.delete();
                }
            }
        }
    }

    public static Material material(Context context) throws Exception {
        Context appContext = appContext(context);
        if (appContext == null) {
            throw new IllegalArgumentException("missing context");
        }
        prepare(context);
        File filesDir = appContext.getFilesDir();
        char[] password = password(appContext);
        KeyStore load = load(new File(filesDir, CA_STORE), password);
        return new Material(load, load(new File(filesDir, SERVER_STORE), password), password, (X509Certificate) load.getCertificate(CA_ALIAS));
    }

    public static String exportCaCertificate(Context context) throws Exception {
        if (appContext(context) == null) {
            throw new IllegalArgumentException("missing context");
        }
        prepare(context);
        return writeDownload(context, "Deekseep-Local-API-CA.cer", "application/x-x509-ca-cert", ((X509Certificate) material(context).authorityStore.getCertificate(CA_ALIAS)).getEncoded());
    }

    public static String exportRootModule(Context context) throws Exception {
        if (appContext(context) == null) {
            throw new IllegalArgumentException("missing context");
        }
        prepare(context);
        X509Certificate x509Certificate = (X509Certificate) material(context).authorityStore.getCertificate(CA_ALIAS);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream);
        writeEntry(zipOutputStream, "module.prop", "id=dq0_ca\nname=Deekseep Local API CA\nversion=1.0\nversionCode=1\nauthor=Deekseep\ndescription=Trust the per-device Deekseep Local API HTTPS CA\n".getBytes("UTF-8"));
        writeEntry(zipOutputStream, "customize.sh", rootInstaller().getBytes("UTF-8"));
        writeEntry(zipOutputStream, "system/etc/security/cacerts/" + legacyFileName(context, x509Certificate), pem(x509Certificate));
        zipOutputStream.finish();
        zipOutputStream.close();
        return writeDownload(context, "Deekseep-CA-Root.zip", "application/zip", byteArrayOutputStream.toByteArray());
    }

    public static String fingerprint(X509Certificate x509Certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(x509Certificate.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format(Locale.US, "%02X", Integer.valueOf(digest[i] & 255)));
        }
        return sb.toString();
    }

    public static List<String> subjectNames() {
        ArrayList arrayList = new ArrayList();
        arrayList.add("127.0.0.1");
        arrayList.add("localhost");
        String lanAddress = lanAddress();
        if (lanAddress != null) {
            arrayList.add(lanAddress);
        }
        return arrayList;
    }

    public static String lanAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface nextElement = networkInterfaces.nextElement();
                if (nextElement.isUp() && !nextElement.isLoopback() && !nextElement.isVirtual()) {
                    Enumeration<InetAddress> inetAddresses = nextElement.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress nextElement2 = inetAddresses.nextElement();
                        if ((nextElement2 instanceof Inet4Address) && !nextElement2.isLoopbackAddress()) {
                            return nextElement2.getHostAddress();
                        }
                    }
                }
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    public static void markVerified(Context context) {
        Context appContext = appContext(context);
        if (appContext == null) {
            return;
        }
        try {
            LocalApiConfig.writeAtomic(new File(appContext.getFilesDir(), VERIFIED), new byte[]{49});
        } catch (Throwable th) {
        }
    }

    public static boolean isVerified(Context context) {
        Context appContext = appContext(context);
        return appContext != null && new File(appContext.getFilesDir(), VERIFIED).isFile();
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        Context applicationContext = context.getApplicationContext();
        return applicationContext == null ? context : applicationContext;
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
    }

    private static char[] password(Context context) throws Exception {
        File file = new File(context.getFilesDir(), PASSWORD_FILE);
        if (file.isFile()) {
            return new String(LocalApiConfig.readAll(file), "US-ASCII").toCharArray();
        }
        byte[] bArr = new byte[32];
        new SecureRandom().nextBytes(bArr);
        String encodeToString = Base64.encodeToString(bArr, 2);
        LocalApiConfig.writeAtomic(file, encodeToString.getBytes("US-ASCII"));
        return encodeToString.toCharArray();
    }

    private static KeyStore load(File file, char[] cArr) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            keyStore.load(fileInputStream, cArr);
            return keyStore;
        } finally {
            fileInputStream.close();
        }
    }

    private static void writeStore(File file, KeyStore keyStore, char[] cArr) throws Exception {
        LocalApiConfig.writeAtomic(file, serialize(keyStore, cArr));
    }

    private static byte[] serialize(KeyStore keyStore, char[] cArr) throws Exception {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        keyStore.store(byteArrayOutputStream, cArr);
        return byteArrayOutputStream.toByteArray();
    }

    private static boolean covers(X509Certificate x509Certificate, List<String> list) throws Exception {
        boolean z;
        Collection<List<?>> subjectAlternativeNames = x509Certificate.getSubjectAlternativeNames();
        if (subjectAlternativeNames == null) {
            return false;
        }
        Iterator<String> it = list.iterator();
        do {
            z = true;
            if (!it.hasNext()) {
                return true;
            }
            String next = it.next();
            Iterator<List<?>> it2 = subjectAlternativeNames.iterator();
            while (true) {
                if (!it2.hasNext()) {
                    z = false;
                    break;
                }
                List<?> next2 = it2.next();
                if (next2.size() > 1 && next.equals(String.valueOf(next2.get(1)))) {
                    break;
                }
            }
        } while (z);
        return false;
    }

    private static String legacyFileName(Context context, X509Certificate x509Certificate) throws Exception {
        return legacyName(x509Certificate) + ".0";
    }

    private static String legacyName(X509Certificate x509Certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(x509Certificate.getEncoded());
        return String.format(Locale.US, "%08x", Integer.valueOf((digest[0] & 255) | ((digest[3] & 255) << 24) | ((digest[2] & 255) << 16) | ((digest[1] & 255) << 8)));
    }

    private static byte[] pem(X509Certificate x509Certificate) throws Exception {
        int i = 0;
        String encodeToString = Base64.encodeToString(x509Certificate.getEncoded(), 0);
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        while (i < encodeToString.length()) {
            int i2 = i + 64;
            sb.append((CharSequence) encodeToString, i, Math.min(encodeToString.length(), i2)).append('\n');
            i = i2;
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString().getBytes("US-ASCII");
    }

    private static String rootInstaller() {
        return "#!/system/bin/sh\nui_print \"- Deekseep Local API CA\"\nui_print \"- Install <?xml version='1.0'?> certificates into the system store\"\nset_perm_recursive $MODPATH 0 0 0755 0644\nset_perm $MODPATH/system/etc/security/cacerts/*.0 0 0 0644\nui_print \"- Reboot after installation\"\n";
    }

    private static void writeEntry(ZipOutputStream zipOutputStream, String str, byte[] bArr) throws Exception {
        ZipEntry zipEntry = new ZipEntry(str);
        zipEntry.setTime(0L);
        zipOutputStream.putNextEntry(zipEntry);
        zipOutputStream.write(bArr);
        zipOutputStream.closeEntry();
    }

    private static String writeDownload(Context context, String str, String str2, byte[] bArr) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            File externalStoragePublicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!externalStoragePublicDirectory.isDirectory() && !externalStoragePublicDirectory.mkdirs()) {
                throw new IOException("could not create Downloads directory");
            }
            File file = new File(externalStoragePublicDirectory, str);
            FileOutputStream fileOutputStream = new FileOutputStream(file, false);
            try {
                fileOutputStream.write(bArr);
                fileOutputStream.getFD().sync();
                return file.getAbsolutePath();
            } finally {
                fileOutputStream.close();
            }
        }
        ContentValues contentValues = new ContentValues();
        contentValues.put("_display_name", str);
        contentValues.put("mime_type", str2);
        contentValues.put("relative_path", Environment.DIRECTORY_DOWNLOADS);
        Uri insert = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
        if (insert == null) {
            throw new IOException("Downloads provider rejected file");
        }
        OutputStream openOutputStream = context.getContentResolver().openOutputStream(insert, "w");
        if (openOutputStream == null) {
            throw new IOException("could not open Downloads file");
        }
        try {
            openOutputStream.write(bArr);
            openOutputStream.flush();
            return str;
        } finally {
            openOutputStream.close();
        }
    }

    private static X509Certificate selfSignedAuthority(KeyPair keyPair) throws Exception {
        return assemble(toBeSigned(BigInteger.ONE, "CN=Deekseep Local API CA", keyPair.getPublic(), null, true, keyPair.getPublic()), keyPair.getPrivate());
    }

    private static X509Certificate issueLeaf(X509Certificate x509Certificate, PrivateKey privateKey, PublicKey publicKey) throws Exception {
        return assemble(toBeSigned(serial(), x509Certificate.getSubjectDN().getName(), publicKey, subjectNames(), false, x509Certificate.getPublicKey()), privateKey);
    }

    private static BigInteger serial() {
        byte[] bArr = new byte[8];
        new SecureRandom().nextBytes(bArr);
        return new BigInteger(1, bArr);
    }

    private static X509Certificate assemble(byte[] bArr, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(bArr);
        byte[] sign = signature.sign();
        Der der = new Der();
        der.sequenceStart();
        der.writeRaw(bArr);
        der.sequenceStart();
        der.oid(SIGNATURE_OID);
        der.nullValue();
        der.sequenceEnd();
        der.tagWrite(3, prefixedBitString(sign));
        der.sequenceEnd();
        return parse(der);
    }

    private static byte[] toBeSigned(BigInteger bigInteger, String str, PublicKey publicKey, List<String> list, boolean z, PublicKey publicKey2) throws Exception {
        Der der = new Der();
        der.sequenceStart();
        der.explicit(0, der.integerBytes(BigInteger.valueOf(2L)));
        der.integer(bigInteger);
        der.sequenceStart();
        der.oid(SIGNATURE_OID);
        der.nullValue();
        der.sequenceEnd();
        der.name(str);
        der.validity(System.currentTimeMillis(), System.currentTimeMillis() + VALIDITY_MS);
        der.name("CN=Deekseep Local API");
        der.subjectPublicKeyInfo(publicKey);
        der.explicit(3, extensions(list, z, publicKey2));
        der.sequenceEnd();
        return der.toByteArray();
    }

    private static byte[] extensions(List<String> list, boolean z, PublicKey publicKey) throws Exception {
        Der der = new Der();
        der.sequenceStart();
        der.writeRaw(extension("2.5.29.19", true, z ? new byte[]{48, 3, 1, 1, -1} : new byte[]{48, 0}));
        der.writeRaw(extension("2.5.29.15", true, keyUsage(z)));
        der.writeRaw(extension("2.5.29.14", false, keyIdentifier(publicKey)));
        if (!z) {
            Der der2 = new Der();
            der2.sequenceStart();
            der2.writeRaw(der2.oidBytes("1.3.6.1.5.5.7.3.1"));
            der2.sequenceEnd();
            der.writeRaw(extension("2.5.29.37", false, der2.toByteArray()));
        }
        if (list != null && !list.isEmpty()) {
            der.writeRaw(extension("2.5.29.17", false, generalNames(list)));
        }
        der.sequenceEnd();
        return der.toByteArray();
    }

    private static byte[] extension(String str, boolean z, byte[] bArr) throws Exception {
        Der der = new Der();
        der.sequenceStart();
        der.oid(str);
        if (z) {
            der.booleanValue(true);
        }
        der.octetString(bArr);
        der.sequenceEnd();
        return der.toByteArray();
    }

    private static byte[] keyUsage(boolean z) throws Exception {
        return new byte[]{z ? (byte) 1 : (byte) 6, z ? (byte) -122 : (byte) -96};
    }

    private static byte[] keyIdentifier(PublicKey publicKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(publicKey.getEncoded());
        if (digest.length != 20) {
            throw new IllegalStateException("unexpected key digest length");
        }
        byte[] bArr = new byte[digest.length + 2];
        bArr[0] = 4;
        bArr[1] = 20;
        System.arraycopy(digest, 0, bArr, 2, digest.length);
        return bArr;
    }

    private static byte[] generalNames(List<String> list) throws Exception {
        Der der = new Der();
        der.sequenceStart();
        for (String str : list) {
            byte[] packAddress = packAddress(str);
            if (packAddress == null) {
                der.tagWrite(130, str.getBytes("US-ASCII"));
            } else {
                der.tagWrite(135, packAddress);
            }
        }
        der.sequenceEnd();
        return der.toByteArray();
    }

    private static byte[] packAddress(String str) {
        String[] split = str.split("\\.");
        if (split.length != 4) {
            return null;
        }
        byte[] bArr = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int parseInt = Integer.parseInt(split[i]);
                if (parseInt >= 0 && parseInt <= 255) {
                    bArr[i] = (byte) parseInt;
                }
                return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bArr;
    }

    private static byte[] prefixedBitString(byte[] bArr) {
        byte[] bArr2 = new byte[bArr.length + 1];
        bArr2[0] = 0;
        System.arraycopy(bArr, 0, bArr2, 1, bArr.length);
        return bArr2;
    }

    private static X509Certificate parse(Der der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der.toByteArray()));
    }
}
