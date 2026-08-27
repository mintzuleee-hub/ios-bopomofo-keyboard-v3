package com.custom.iosime;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BopomofoEngine {
    private static final String DB_ZH = "phonetic.db";
    private static final String DB_EN = "dictionary.db";
    private SQLiteDatabase dbZh;
    private SQLiteDatabase dbEn;
    private final Context context;

    // 四個聲調符號：用來判斷一個音節是否已經「完成」（已經按過聲調鍵）
    private static final String TONE_CHARS = "ˊˇˋ˙";

    private static final Map<String, String> BPMF_MAP = new HashMap<>();

    static {
        BPMF_MAP.put("ㄅ", "1"); BPMF_MAP.put("ㄆ", "q"); BPMF_MAP.put("ㄇ", "a"); BPMF_MAP.put("ㄈ", "z");
        BPMF_MAP.put("ㄉ", "2"); BPMF_MAP.put("ㄊ", "w"); BPMF_MAP.put("ㄋ", "s"); BPMF_MAP.put("ㄌ", "x");
        BPMF_MAP.put("ㄍ", "e"); BPMF_MAP.put("ㄎ", "d"); BPMF_MAP.put("ㄏ", "c"); BPMF_MAP.put("ㄐ", "r");
        BPMF_MAP.put("ㄑ", "f"); BPMF_MAP.put("ㄒ", "v"); BPMF_MAP.put("ㄓ", "5"); BPMF_MAP.put("ㄔ", "t");
        BPMF_MAP.put("ㄕ", "g"); BPMF_MAP.put("ㄖ", "b"); BPMF_MAP.put("ㄗ", "y"); BPMF_MAP.put("ㄘ", "h");
        BPMF_MAP.put("ㄙ", "n"); BPMF_MAP.put("ㄧ", "u"); BPMF_MAP.put("ㄨ", "j"); BPMF_MAP.put("ㄩ", "m");
        BPMF_MAP.put("ㄚ", "8"); BPMF_MAP.put("ㄛ", "i"); BPMF_MAP.put("ㄜ", "k"); BPMF_MAP.put("ㄝ", ",");
        BPMF_MAP.put("ㄞ", "9"); BPMF_MAP.put("ㄟ", "o"); BPMF_MAP.put("ㄠ", "l"); BPMF_MAP.put("ㄡ", ".");
        BPMF_MAP.put("ㄢ", "0"); BPMF_MAP.put("ㄣ", "p"); BPMF_MAP.put("ㄤ", ";"); BPMF_MAP.put("ㄥ", "/");
        BPMF_MAP.put("ㄦ", "-"); BPMF_MAP.put("ˊ", "6"); BPMF_MAP.put("ˇ", "3"); BPMF_MAP.put("ˋ", "4");
        BPMF_MAP.put("˙", "7");
    }

    public BopomofoEngine(Context context) {
        this.context = context;
        initDatabases();
    }

    private void initDatabases() {
        copyAssetDb(DB_ZH);
        copyAssetDb(DB_EN);
        try {
            dbZh = SQLiteDatabase.openDatabase(context.getDatabasePath(DB_ZH).getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {}
        try {
            dbEn = SQLiteDatabase.openDatabase(context.getDatabasePath(DB_EN).getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {}
    }

    private void copyAssetDb(String dbName) {
        File dbFile = context.getDatabasePath(dbName);
        long assetSize = -1;
        try {
            android.content.res.AssetFileDescriptor afd = context.getAssets().openFd(dbName);
            assetSize = afd.getLength();
            afd.close();
        } catch (Exception ignored) {}

        // 檔案不存在，或跟 assets 裡的大小對不起來（代表資料庫更新過），才需要重新複製；
        // 避免每次開鍵盤都重寫檔案，也避免舊安裝殘留的舊資料庫一直被誤用。
        boolean needsCopy = !dbFile.exists() || dbFile.length() < 1000
                || (assetSize > 0 && dbFile.length() != assetSize);

        if (needsCopy) {
            if (dbFile.getParentFile() != null) dbFile.getParentFile().mkdirs();
            try (InputStream is = context.getAssets().open(dbName);
                 FileOutputStream fos = new FileOutputStream(dbFile)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            } catch (Exception e) {}
        }
    }

    public String convertBpmfToCode(String bpmf) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bpmf.length(); i++) {
            String ch = String.valueOf(bpmf.charAt(i));
            sb.append(BPMF_MAP.getOrDefault(ch, ch));
        }
        return sb.toString();
    }

    public List<CandidateItem> getCandidates(String bopomofoQuery) {
        List<CandidateItem> list = new ArrayList<>();
        if (dbZh == null || !dbZh.isOpen() || bopomofoQuery == null || bopomofoQuery.isEmpty()) return list;

        // 把整段輸入切成一個個「音節」：每個音節在按下聲調符號時結束；
        // 最後一段如果還沒按聲調，代表「正在輸入中」，維持逐字前綴比對的即時候選字體驗。
        List<String> syllables = splitIntoSyllables(bopomofoQuery);
        int n = syllables.size();
        String lastSeg = syllables.get(n - 1);
        int precedingRawLen = bopomofoQuery.length() - lastSeg.length();

        // (A) 只針對「目前這個音節」做逐字前綴比對，而不是整個緩衝區從頭比對，
        //     這樣不管前面已經連打了幾個字，候選字都會正確對應到現在正在打的這個字。
        for (int len = lastSeg.length(); len >= 1; len--) {
            String subBpmf = lastSeg.substring(0, len);
            String subCode = convertBpmfToCode(subBpmf);
            queryPhonetic(subCode, subCode + "%", precedingRawLen + len, list);
        }

        // (B) 連續輸入好幾個字時，把「已經打完聲調」的音節各自取最常用的字接起來，
        //     放在候選字最前面，讓使用者可以像 iOS 注音一樣直接整段選走。
        boolean lastComplete = !lastSeg.isEmpty() && TONE_CHARS.indexOf(lastSeg.charAt(lastSeg.length() - 1)) >= 0;
        int completeSyllableCount = lastComplete ? n : n - 1;
        if (completeSyllableCount >= 2) {
            StringBuilder guess = new StringBuilder();
            boolean ok = true;
            for (int i = 0; i < completeSyllableCount; i++) {
                String best = bestSingleCharFor(syllables.get(i));
                if (best == null) { ok = false; break; }
                guess.append(best);
            }
            if (ok && guess.length() > 0) {
                int consumedRaw = 0;
                for (int i = 0; i < completeSyllableCount; i++) consumedRaw += syllables.get(i).length();
                list.add(0, new CandidateItem(guess.toString(), consumedRaw));
            }
        }

        return list;
    }

    // 把原始注音字串切成音節：每遇到一個聲調符號就結束一段。
    // 最後一段若沒有聲調符號結尾，代表還在輸入中，仍會回傳（當作最後一個元素）。
    private List<String> splitIntoSyllables(String raw) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            cur.append(c);
            if (TONE_CHARS.indexOf(c) >= 0) {
                result.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) result.add(cur.toString());
        if (result.isEmpty()) result.add("");
        return result;
    }

    // 給定一個「已完成」的音節（含聲調），回傳資料庫裡分數最高的單字。
    // 限制 length(word) = 1，避免跟詞語的簡碼撞碼（詞語的簡碼有時跟某個單字的完整碼一樣）。
    private String bestSingleCharFor(String completedSyllableRaw) {
        if (dbZh == null || !dbZh.isOpen()) return null;
        String code = convertBpmfToCode(completedSyllableRaw);
        Cursor cursor = null;
        try {
            cursor = dbZh.rawQuery(
                "SELECT word FROM phonetic WHERE code = ? AND word IS NOT NULL AND length(word) = 1 ORDER BY score DESC LIMIT 1",
                new String[]{ code });
            if (cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception e) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    // 提供給輸入法在按空白鍵時用：把一段原始注音（可能包含好幾個已完成的音節）
    // 轉成「目前最佳猜測」的文字，供空白鍵一次把前面連打的字一起送出。
    public String bestGuessForPrefix(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        List<String> syl = splitIntoSyllables(raw);
        StringBuilder sb = new StringBuilder();
        for (String s : syl) {
            if (s.isEmpty()) continue;
            String best = bestSingleCharFor(s);
            if (best != null) sb.append(best);
        }
        return sb.toString();
    }

    // 精確比對（非前綴）：專門給「還沒選聲調、要當第一聲處理」的情境用，
    // 因為前綴比對會把各種聲調混在一起，選出來的不一定是第一聲的字。
    public List<CandidateItem> getExactMatch(String bopomofoQuery) {
        List<CandidateItem> list = new ArrayList<>();
        if (dbZh == null || !dbZh.isOpen() || bopomofoQuery == null || bopomofoQuery.isEmpty()) return list;
        String code = convertBpmfToCode(bopomofoQuery);
        Cursor cursor = null;
        try {
            cursor = dbZh.rawQuery(
                "SELECT word FROM phonetic WHERE code = ? AND word IS NOT NULL AND length(word) = 1 ORDER BY score DESC LIMIT 30",
                new String[]{ code });
            while (cursor.moveToNext()) {
                String w = cursor.getString(0);
                if (w != null && !w.isEmpty() && !containsWord(list, w)) {
                    list.add(new CandidateItem(w, bopomofoQuery.length()));
                }
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    private void queryPhonetic(String exactCode, String prefixCode, int consumedLen, List<CandidateItem> list) {
        Cursor cursor = null;
        try {
            cursor = dbZh.rawQuery(
                "SELECT word, related FROM phonetic WHERE word IS NOT NULL AND (code = ? OR code LIKE ?) ORDER BY (code = ?) DESC, length(word) DESC, score DESC LIMIT 40",
                new String[]{ exactCode, prefixCode, exactCode }
            );
            while (cursor.moveToNext()) {
                String word = cursor.getString(0);
                if (word != null && !word.isEmpty() && !containsWord(list, word)) {
                    list.add(new CandidateItem(word, consumedLen));
                }
                String related = cursor.getString(1);
                if (related != null && !related.isEmpty()) {
                    String[] relWords = related.split("\\|");
                    for (String r : relWords) {
                        if (!r.isEmpty() && !containsWord(list, r) && list.size() < 50) {
                            list.add(new CandidateItem(r, consumedLen));
                        }
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public List<CandidateItem> getAssociationWords(String lastWord) {
        List<CandidateItem> list = new ArrayList<>();
        if (dbZh == null || !dbZh.isOpen() || lastWord == null || lastWord.isEmpty()) return list;

        Cursor cursor = null;
        try {
            cursor = dbZh.rawQuery(
                "SELECT related FROM phonetic WHERE word = ? AND related IS NOT NULL LIMIT 1",
                new String[]{ lastWord }
            );
            if (cursor.moveToFirst()) {
                String related = cursor.getString(0);
                if (related != null && !related.isEmpty()) {
                    String[] relWords = related.split("\\|");
                    for (String r : relWords) {
                        if (!r.isEmpty() && !containsWord(list, r)) {
                            list.add(new CandidateItem(r, 0));
                        }
                    }
                }
            }
        } catch (Exception e) {}
        finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public List<CandidateItem> getEnglishCandidates(String prefix) {
        List<CandidateItem> list = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return list;

        if (dbEn != null && dbEn.isOpen()) {
            try (Cursor cursor = dbEn.rawQuery(
                "SELECT word FROM dictionary_data WHERE word LIKE ? ORDER BY length(word) ASC, basescore DESC LIMIT 30",
                new String[]{ prefix.toLowerCase() + "%" })) {
                while (cursor.moveToNext()) {
                    String w = cursor.getString(0);
                    if (!containsWord(list, w)) list.add(new CandidateItem(w, prefix.length()));
                }
            } catch (Exception e) {}
        }
        return list;
    }

    private boolean containsWord(List<CandidateItem> list, String word) {
        for (CandidateItem item : list) {
            if (item.text.equals(word)) return true;
        }
        return false;
    }

    public static class CandidateItem {
        public String text;
        public int consumedLength;

        public CandidateItem(String text, int consumedLength) {
            this.text = text;
            this.consumedLength = consumedLength;
        }
    }
}
