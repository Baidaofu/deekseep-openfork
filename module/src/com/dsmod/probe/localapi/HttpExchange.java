package com.dsmod.probe.localapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class HttpExchange {
    private static final int MAX_BODY_BYTES = 67108864;
    private static final int MAX_HEADER_LINES = 128;
    public final Map<String, String> headers;
    private boolean headersWritten;
    public final InputStream input;
    public final String method;
    public final OutputStream output;
    public final String path;
    public final String rawTarget;

    private HttpExchange(String str, String str2, String str3, Map<String, String> map, InputStream inputStream, OutputStream outputStream) {
        this.method = str;
        this.rawTarget = str2;
        this.path = str3;
        this.headers = map;
        this.input = inputStream;
        this.output = outputStream;
    }

    static HttpExchange parse(InputStream inputStream, OutputStream outputStream) throws IOException {
        String readLine = readLine(inputStream);
        if (readLine == null || readLine.isEmpty()) {
            return null;
        }
        String[] split = readLine.split(" ");
        if (split.length < 2) {
            throw new IOException("malformed request line");
        }
        String upperCase = split[0].toUpperCase(Locale.US);
        String str = split[1];
        HashMap hashMap = new HashMap();
        while (true) {
            String readLine2 = readLine(inputStream);
            if (readLine2 == null || readLine2.isEmpty()) {
                break;
            }
            int indexOf = readLine2.indexOf(58);
            if (indexOf > 0) {
                hashMap.put(readLine2.substring(0, indexOf).trim().toLowerCase(Locale.US), readLine2.substring(indexOf + 1).trim());
            }
        }
        return new HttpExchange(upperCase, str, stripQuery(str), Collections.unmodifiableMap(hashMap), inputStream, outputStream);
    }

    private static String stripQuery(String str) {
        int indexOf = str.indexOf(63);
        if (indexOf >= 0) {
            str = str.substring(0, indexOf);
        }
        if (str.length() > 1 && str.endsWith("/")) {
            return str.substring(0, str.length() - 1);
        }
        return str;
    }

    public String path() {
        return this.path;
    }

    public String header(String str) {
        return this.headers.get(str.toLowerCase(Locale.US));
    }

    public byte[] body() throws IOException {
        String header = header("transfer-encoding");
        if (header != null && header.toLowerCase(Locale.US).contains("chunked")) {
            return readChunked(this.input);
        }
        String header2 = header("content-length");
        int i = 0;
        if (header2 == null) {
            return new byte[0];
        }
        try {
            int parseInt = Integer.parseInt(header2.trim());
            if (parseInt <= 0) {
                return new byte[0];
            }
            if (parseInt > MAX_BODY_BYTES) {
                throw new IOException("request body too large");
            }
            byte[] bArr = new byte[parseInt];
            while (i < parseInt) {
                int read = this.input.read(bArr, i, parseInt - i);
                if (read < 0) {
                    break;
                }
                i += read;
            }
            return bArr;
        } catch (NumberFormatException e) {
            return new byte[0];
        }
    }

    private static byte[] readChunked(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        while (true) {
            String readLine = readLine(inputStream);
            if (readLine == null) {
                break;
            }
            int indexOf = readLine.indexOf(59);
            if (indexOf >= 0) {
                readLine = readLine.substring(0, indexOf);
            }
            try {
                int parseInt = Integer.parseInt(readLine.trim(), 16);
                if (parseInt == 0) {
                    readLine(inputStream);
                    break;
                }
                if (byteArrayOutputStream.size() + parseInt > MAX_BODY_BYTES) {
                    throw new IOException("request body too large");
                }
                byte[] bArr = new byte[parseInt];
                int i = 0;
                while (i < parseInt) {
                    int read = inputStream.read(bArr, i, parseInt - i);
                    if (read < 0) {
                        break;
                    }
                    i += read;
                }
                byteArrayOutputStream.write(bArr, 0, i);
                readLine(inputStream);
            } catch (NumberFormatException e) {
                throw new IOException("malformed chunk size");
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    public void respond(int i, String str, byte[] bArr) throws IOException {
        if (bArr == null) {
            bArr = new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(i).append(' ').append(reason(i)).append("\r\n");
        sb.append("Content-Type: ").append(str).append("\r\n");
        sb.append("Content-Length: ").append(bArr.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        this.output.write(sb.toString().getBytes("UTF-8"));
        this.output.write(bArr);
        this.output.flush();
        this.headersWritten = true;
    }

    public void startStreaming(int i, String str) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(i).append(' ').append(reason(i)).append("\r\n");
        sb.append("Content-Type: ").append(str).append("\r\n");
        sb.append("Cache-Control: no-cache\r\n");
        sb.append("X-Accel-Buffering: no\r\n");
        sb.append("Transfer-Encoding: chunked\r\n");
        sb.append("Connection: close\r\n\r\n");
        this.output.write(sb.toString().getBytes("UTF-8"));
        this.output.flush();
        this.headersWritten = true;
    }

    public void writeChunk(byte[] bArr) throws IOException {
        if (bArr.length == 0) {
            return;
        }
        this.output.write(Integer.toHexString(bArr.length).getBytes("UTF-8"));
        this.output.write("\r\n".getBytes("UTF-8"));
        this.output.write(bArr);
        this.output.write("\r\n".getBytes("UTF-8"));
        this.output.flush();
    }

    public void writeSse(String str) throws IOException {
        writeChunk(("data: " + str + "\n\n").getBytes("UTF-8"));
    }

    public void endStreaming() throws IOException {
        this.output.write("0\r\n\r\n".getBytes("UTF-8"));
        this.output.flush();
    }

    public boolean headersWritten() {
        return this.headersWritten;
    }

    private static String reason(int i) {
        switch (i) {
            case 200:
                return "OK";
            case 201:
                return "Created";
            case 204:
                return "No Content";
            case LocalApiStats.LOG_LINES /* 400 */:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 408:
                return "Request Timeout";
            case 413:
                return "Payload Too Large";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            case 502:
                return "Bad Gateway";
            case 503:
                return "Service Unavailable";
            case 504:
                return "Gateway Timeout";
            default:
                return "Error";
        }
    }

    private static String readLine(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        int i = 0;
        while (true) {
            int read = inputStream.read();
            if (read < 0) {
                if (byteArrayOutputStream.size() == 0) {
                    return null;
                }
            } else {
                i++;
                if (i > 16384) {
                    throw new IOException("header line too long");
                }
                if (read == 10) {
                    break;
                }
                if (read != 13) {
                    byteArrayOutputStream.write(read);
                }
            }
        }
        return new String(byteArrayOutputStream.toByteArray(), "UTF-8");
    }

    static int maxHeaderLines() {
        return MAX_HEADER_LINES;
    }
}
