package com.dsmod.probe.localapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ModelCatalog {
    private static final String[][] STOCK = {new String[]{"deepseek-chat", ApiContract.MODEL_DEFAULT}, new String[]{"deepseek-reasoner", ApiContract.MODEL_EXPERT}, new String[]{"deepseek-vision", ApiContract.MODEL_VISION}};
    private static final String[][] INPUT_ALIASES = {new String[]{"deepseek-v4-flash", ApiContract.MODEL_DEFAULT}, new String[]{"deepseek-v4-pro", ApiContract.MODEL_EXPERT}, new String[]{"deepseek-r1", ApiContract.MODEL_EXPERT}, new String[]{"reasoner", ApiContract.MODEL_EXPERT}};

    public static final class Entry {
        public final String id;
        public final String ownedBy;
        public final String role;

        Entry(String str, String str2, String str3) {
            this.id = str;
            this.ownedBy = str2;
            this.role = str3;
        }
    }

    private ModelCatalog() {
    }

    public static List<Entry> advertised(List<Object> list) {
        ArrayList arrayList = new ArrayList();
        for (String[] strArr : STOCK) {
            arrayList.add(new Entry(strArr[0], "deepseek", strArr[1]));
        }
        Iterator<Object> it = list.iterator();
        while (it.hasNext()) {
            Entry fromCustom = fromCustom(it.next());
            if (fromCustom != null) {
                arrayList.add(fromCustom);
            }
        }
        return arrayList;
    }

    public static String resolveRole(String str, List<Object> list) {
        if (str == null || str.trim().isEmpty()) {
            return ApiContract.MODEL_DEFAULT;
        }
        String lowerCase = str.trim().toLowerCase(Locale.US);
        for (String[] strArr : STOCK) {
            if (strArr[0].equals(lowerCase)) {
                return strArr[1];
            }
        }
        for (String[] strArr2 : INPUT_ALIASES) {
            if (strArr2[0].equals(lowerCase)) {
                return strArr2[1];
            }
        }
        Iterator<Object> it = list.iterator();
        while (it.hasNext()) {
            Entry fromCustom = fromCustom(it.next());
            if (fromCustom != null && fromCustom.id.equalsIgnoreCase(lowerCase)) {
                return fromCustom.role;
            }
        }
        return ApiContract.MODEL_DEFAULT;
    }

    public static boolean isAuxiliary(String str) {
        if (str == null) {
            return false;
        }
        String lowerCase = str.toLowerCase(Locale.US);
        return lowerCase.equals("deepseek-aux") || lowerCase.startsWith("deepseek-aux-");
    }

    public static Set<String> ids(List<Entry> list) {
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            linkedHashSet.add(it.next().id);
        }
        return Collections.unmodifiableSet(linkedHashSet);
    }

    private static Entry fromCustom(Object obj) {
        String str;
        String str2;
        if (obj == null) {
            return null;
        }
        boolean z = obj instanceof JSONObject;
        String str3 = ApiContract.MODEL_DEFAULT;
        if (z) {
            JSONObject jSONObject = (JSONObject) obj;
            str = trimToNull(jSONObject.optString("id", null));
            str2 = trimToNull(jSONObject.optString("native_model", null));
            if (str2 == null) {
                str2 = ApiContract.MODEL_DEFAULT;
            }
        } else if (!(obj instanceof String)) {
            str = null;
            str2 = ApiContract.MODEL_DEFAULT;
        } else {
            str = trimToNull((String) obj);
            str2 = ApiContract.MODEL_DEFAULT;
        }
        if (str == null) {
            return null;
        }
        String lowerCase = str2 == null ? ApiContract.MODEL_DEFAULT : str2.toLowerCase(Locale.US);
        if (ApiContract.MODEL_DEFAULT.equals(lowerCase) || ApiContract.MODEL_EXPERT.equals(lowerCase) || ApiContract.MODEL_VISION.equals(lowerCase)) {
            str3 = lowerCase;
        }
        return new Entry(str, "deepseek", str3);
    }

    public static List<Object> parseCustom(String str) {
        ArrayList arrayList = new ArrayList();
        if (str == null || str.trim().isEmpty()) {
            return arrayList;
        }
        try {
            JSONArray jSONArray = new JSONArray(str);
            for (int i = 0; i < jSONArray.length(); i++) {
                Object opt = jSONArray.opt(i);
                if (fromCustom(opt) != null) {
                    arrayList.add(opt);
                }
            }
        } catch (Throwable th) {
        }
        return arrayList;
    }

    private static String trimToNull(String str) {
        if (str == null) {
            return null;
        }
        String trim = str.trim();
        if (trim.isEmpty()) {
            return null;
        }
        return trim;
    }
}
