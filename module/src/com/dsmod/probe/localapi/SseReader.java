package com.dsmod.probe.localapi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import org.json.JSONException;
import org.json.JSONObject;

public final class SseReader {
    public static final String DONE = "[DONE]";

    public interface Visitor {
        boolean isCancelled();

        void onEvent(String str);
    }

    private SseReader() {
    }

    public static boolean read(InputStream inputStream, Visitor visitor) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        while (true) {
            String readLine = bufferedReader.readLine();
            if (readLine != null) {
                if (visitor.isCancelled()) {
                    return false;
                }
                if (readLine.isEmpty()) {
                    if (sb.length() > 0) {
                        String sb2 = sb.toString();
                        sb.setLength(0);
                        if (DONE.equals(sb2.trim())) {
                            return true;
                        }
                        visitor.onEvent(sb2);
                    } else {
                        continue;
                    }
                } else if (readLine.charAt(0) != ':' && readLine.startsWith("data:")) {
                    String substring = readLine.substring("data:".length());
                    if (!substring.isEmpty() && substring.charAt(0) == ' ') {
                        substring = substring.substring(1);
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(substring);
                }
            } else {
                if (sb.length() > 0 && DONE.equals(sb.toString().trim())) {
                    return true;
                }
                if (sb.length() > 0) {
                    visitor.onEvent(sb.toString());
                }
                return false;
            }
        }
    }

    public static JSONObject asJson(String str) {
        String trim = str == null ? "" : str.trim();
        if (!trim.startsWith("{")) {
            return null;
        }
        try {
            return new JSONObject(trim);
        } catch (JSONException e) {
            return null;
        }
    }
}
