package com.dsmod.probe.localapi;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.PublicKey;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.TimeZone;

final class Der {
    private final Deque<ByteArrayOutputStream> stack = new ArrayDeque();
    private final ByteArrayOutputStream root = new ByteArrayOutputStream();

    Der() {
        this.stack.push(this.root);
    }

    private ByteArrayOutputStream current() {
        ByteArrayOutputStream peek = this.stack.peek();
        if (peek == null) {
            throw new IllegalStateException("DER stack is empty");
        }
        return peek;
    }

    void sequenceStart() {
        this.stack.push(new ByteArrayOutputStream());
    }

    void sequenceEnd() {
        closeContainer(48);
    }

    void setStart() {
        this.stack.push(new ByteArrayOutputStream());
    }

    void setEnd() {
        closeContainer(49);
    }

    private void closeContainer(int i) {
        emit(current(), i, this.stack.pop().toByteArray());
    }

    void writeRaw(byte[] bArr) {
        current().write(bArr, 0, bArr.length);
    }

    void integer(BigInteger bigInteger) {
        writeRaw(integerBytes(bigInteger));
    }

    byte[] integerBytes(BigInteger bigInteger) {
        byte[] byteArray = bigInteger.toByteArray();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(2);
        writeLength(byteArrayOutputStream, byteArray.length);
        byteArrayOutputStream.write(byteArray, 0, byteArray.length);
        return byteArrayOutputStream.toByteArray();
    }

    void booleanValue(boolean z) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(1);
        writeLength(byteArrayOutputStream, 1);
        byteArrayOutputStream.write(z ? 255 : 0);
        writeRaw(byteArrayOutputStream.toByteArray());
    }

    void nullValue() {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(5);
        writeLength(byteArrayOutputStream, 0);
        writeRaw(byteArrayOutputStream.toByteArray());
    }

    void octetString(byte[] bArr) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(4);
        writeLength(byteArrayOutputStream, bArr.length);
        byteArrayOutputStream.write(bArr, 0, bArr.length);
        writeRaw(byteArrayOutputStream.toByteArray());
    }

    void utf8String(String str) throws Exception {
        byte[] bytes = str.getBytes("UTF-8");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(12);
        writeLength(byteArrayOutputStream, bytes.length);
        byteArrayOutputStream.write(bytes, 0, bytes.length);
        writeRaw(byteArrayOutputStream.toByteArray());
    }

    void oid(String str) {
        writeRaw(oidBytes(str));
    }

    byte[] oidBytes(String str) {
        String[] split = str.split("\\.");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write((Integer.parseInt(split[0]) * 40) + Integer.parseInt(split[1]));
        for (int i = 2; i < split.length; i++) {
            writeBase128(byteArrayOutputStream, Long.parseLong(split[i]));
        }
        ByteArrayOutputStream byteArrayOutputStream2 = new ByteArrayOutputStream();
        byteArrayOutputStream2.write(6);
        writeLength(byteArrayOutputStream2, byteArrayOutputStream.size());
        byteArrayOutputStream2.write(byteArrayOutputStream.toByteArray(), 0, byteArrayOutputStream.size());
        return byteArrayOutputStream2.toByteArray();
    }

    void utcTime(long j) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] bytes = simpleDateFormat.format(new Date(j)).getBytes();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(23);
        writeLength(byteArrayOutputStream, bytes.length);
        byteArrayOutputStream.write(bytes, 0, bytes.length);
        writeRaw(byteArrayOutputStream.toByteArray());
    }

    void tagWrite(int i, byte[] bArr) {
        emit(current(), i, bArr);
    }

    void explicit(int i, byte[] bArr) {
        emit(current(), i | 160, bArr);
    }

    void algorithmIdentifier(String str) {
        sequenceStart();
        oid(str);
        nullValue();
        sequenceEnd();
    }

    void subjectPublicKeyInfo(PublicKey publicKey) {
        writeRaw(publicKey.getEncoded());
    }

    void name(String str) throws Exception {
        if (str.startsWith("CN=")) {
            str = str.substring(3);
        }
        sequenceStart();
        setStart();
        sequenceStart();
        oid("2.5.4.3");
        utf8String(str);
        sequenceEnd();
        setEnd();
        sequenceEnd();
    }

    void validity(long j, long j2) {
        sequenceStart();
        utcTime(j);
        utcTime(j2);
        sequenceEnd();
    }

    byte[] toByteArray() {
        if (this.stack.size() != 1) {
            throw new IllegalStateException("unbalanced DER containers");
        }
        return this.root.toByteArray();
    }

    private static void emit(ByteArrayOutputStream byteArrayOutputStream, int i, byte[] bArr) {
        byteArrayOutputStream.write(i);
        writeLength(byteArrayOutputStream, bArr.length);
        byteArrayOutputStream.write(bArr, 0, bArr.length);
    }

    private static void writeLength(ByteArrayOutputStream byteArrayOutputStream, int i) {
        if (i < 128) {
            byteArrayOutputStream.write(i);
            return;
        }
        int i2 = 1;
        for (int i3 = i >>> 8; i3 > 0; i3 >>>= 8) {
            i2++;
        }
        byteArrayOutputStream.write(i2 | 128);
        for (int i4 = i2 - 1; i4 >= 0; i4--) {
            byteArrayOutputStream.write((i >>> (i4 * 8)) & 255);
        }
    }

    private static void writeBase128(ByteArrayOutputStream byteArrayOutputStream, long j) {
        long j2 = j;
        int i = 1;
        while (j2 > 127) {
            j2 >>>= 7;
            i++;
        }
        for (int i2 = i - 1; i2 >= 0; i2--) {
            int i3 = (int) ((j >>> (i2 * 7)) & 127);
            if (i2 > 0) {
                i3 |= 128;
            }
            byteArrayOutputStream.write(i3);
        }
    }
}
