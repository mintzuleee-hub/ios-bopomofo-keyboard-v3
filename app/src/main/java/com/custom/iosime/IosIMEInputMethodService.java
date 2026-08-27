package com.custom.iosime;

import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class IosIMEInputMethodService extends InputMethodService {

    private enum Mode {
        BOPOMOFO, ENGLISH, SYMBOLS_1, SYMBOLS_2, EMOJI
    }

    private Mode currentMode = Mode.BOPOMOFO;
    private boolean isPageB = false;
    private boolean isCandidateExpanded = false;
    private boolean isShift = false;

    private LinearLayout keyboardRowsContainer;
    private RecyclerView rvCandidates;
    private RecyclerView rvCandidatesExpanded;
    private TextView tvExpandCandidates;
    private TextView tvComposing;
    private CandidateAdapter candidateAdapter;
    private CandidateAdapter expandedCandidateAdapter;
    private BopomofoEngine engine;
    private android.speech.SpeechRecognizer speechRecognizer;
    private boolean isListening = false;

    // 使用者偏好設定（深色模式 / 聲音 / 震動）
    private boolean isDarkMode = true;
    private boolean soundEnabled = false;
    private boolean vibrationEnabled = true;
    private android.os.Vibrator vibrator;
    private android.media.AudioManager audioManager;

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        isDarkMode = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        soundEnabled = prefs.getBoolean(SettingsActivity.KEY_SOUND, false);
        vibrationEnabled = prefs.getBoolean(SettingsActivity.KEY_VIBRATION, true);
        CandidateAdapter.darkMode = isDarkMode;
        if (vibrator == null) vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (audioManager == null) audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
    }

    private void applyTheme(View root) {
        root.setBackgroundColor(getColor(isDarkMode ? R.color.ios_bg : R.color.ios_bg_light));
        View divider = root.findViewById(R.id.candidate_divider_row);
        if (divider != null) {
            divider.setBackgroundColor(getColor(isDarkMode ? R.color.ios_candidate_divider : R.color.ios_candidate_divider_light));
        }
        if (tvComposing != null) {
            tvComposing.setTextColor(getColor(isDarkMode ? R.color.ios_subtext_color : R.color.ios_subtext_color_light));
        }
        if (tvExpandCandidates != null) {
            tvExpandCandidates.setTextColor(getColor(isDarkMode ? R.color.ios_subtext_color : R.color.ios_subtext_color_light));
        }
        View globeBtn = root.findViewById(R.id.btn_globe);
        View micBtn = root.findViewById(R.id.btn_mic);
        int tint = getColor(isDarkMode ? R.color.ios_text_color : R.color.ios_text_color_light);
        if (globeBtn instanceof android.widget.ImageButton) ((android.widget.ImageButton) globeBtn).setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        if (micBtn instanceof android.widget.ImageButton) ((android.widget.ImageButton) micBtn).setImageTintList(android.content.res.ColorStateList.valueOf(tint));
    }

    private int keyBgNormalRes() {
        return isDarkMode ? R.drawable.key_bg_normal : R.drawable.key_bg_normal_light;
    }

    private int keyBgSpecialRes() {
        return isDarkMode ? R.drawable.key_bg_special : R.drawable.key_bg_special_light;
    }

    private int keyTextColor() {
        return getColor(isDarkMode ? R.color.ios_text_color : R.color.ios_text_color_light);
    }

    private void giveKeyFeedback() {
        try {
            if (vibrationEnabled && vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(12, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(12);
                }
            }
            if (soundEnabled && audioManager != null) {
                audioManager.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD);
            }
        } catch (Exception e) {
            // 震動/音效在少數裝置上可能因權限或硬體限制丟例外，
            // 這只是打字的附加回饋，絕對不能讓它把整個鍵盤搞當機。
        }
    }

    private final StringBuilder composingCode = new StringBuilder();
    private final List<BopomofoEngine.CandidateItem> candidatesList = new ArrayList<>();

    // 長按加速刪除控制
    private final Handler deleteHandler = new Handler(Looper.getMainLooper());
    private int deleteInterval = 50;
    private final Runnable deleteRunnable = new Runnable() {
        @Override
        public void run() {
            handleBackspace();
            deleteInterval = Math.max(30, deleteInterval - 8);
            deleteHandler.postDelayed(this, deleteInterval);
        }
    };

    // 注音 A 頁
    private final String[] ROW_A1 = {"ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ"};
    private final String[] ROW_A2 = {"ㄍ", "ㄎ", "ㄏ", "ㄐ", "ㄑ", "ㄒ", "ㄧ", "ㄨ", "ㄩ"};
    private final String[] ROW_A3 = {"PAGE_TOGGLE", "ㄓ", "ㄔ", "ㄕ", "ㄖ", "ㄗ", "ㄘ", "ㄙ", "BACKSPACE"};

    // 注音 B 頁
    private final String[] ROW_B1 = {"ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ"};
    private final String[] ROW_B2 = {"ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ", "ㄧ", "ㄨ", "ㄩ"};
    private final String[] ROW_B3 = {"PAGE_TOGGLE", "ˊ", "ˇ", "ˋ", "˙", "BACKSPACE"};

    // 英文鍵盤
    private final String[] ROW_EN1 = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
    private final String[] ROW_EN2 = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};
    private final String[] ROW_EN3 = {"SHIFT", "z", "x", "c", "v", "b", "n", "m", "BACKSPACE"};

    // 123 符號第一頁 (注音模式為全形，英文模式為半形)
    private final String[] ROW_NUM1 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    private final String[] ROW_NUM2_ZH = {"-", "/", "：", "；", "（", "）", "$", "&", "@", "”"};
    private final String[] ROW_NUM3_ZH = {"#+=", "。", "，", "、", "？", "！", "「", "」", "BACKSPACE"};

    private final String[] ROW_NUM2_EN = {"-", "/", ":", ";", "(", ")", "$", "&", "@", "\""};
    private final String[] ROW_NUM3_EN = {"#+=", ".", ",", "?", "!", "'", "BACKSPACE"};

    // #+= 符號第二頁
    private final String[] ROW_SYM1 = {"[", "]", "{", "}", "#", "%", "^", "*", "+", "="};
    private final String[] ROW_SYM2 = {"_", "\\", "|", "~", "<", ">", "$", "•", "`", "§"};
    private final String[] ROW_SYM3 = {"123", "...", ",", "?", "!", "'", "BACKSPACE"};

    // 大量 Emoji 列表 (120+ 常用表情與符號)
    private final String[] EMOJIS = {
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
        "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
        "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖",
        "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯",
        "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔",
        "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯", "😦",
        "👍", "👎", "👊", "✊", "🤛", "🤜", "🤞", "✌️", "🤟", "🤘",
        "👌", "🤏", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐",
        "🖖", "👋", "🤙", "💪", "🙏", "👏", "🙌", "👐", "🤲", "🤝",
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
        "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "🔥", "✨",
        "🎉", "🎊", "💯", "🎈", "🎂", "🎁", "📱", "💻", "💡", "💰"
    };

    @Override
    public View onCreateInputView() {
        loadSettings();
        View root = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null);
        cachedRootView = root;
        keyboardRowsContainer = root.findViewById(R.id.keyboard_rows_container);
        rvCandidates = root.findViewById(R.id.rv_candidates);
        rvCandidatesExpanded = root.findViewById(R.id.rv_candidates_expanded);
        tvExpandCandidates = root.findViewById(R.id.tv_expand_candidates);
        tvComposing = root.findViewById(R.id.tv_composing);

        applyTheme(root);

        rvCandidates.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        candidateAdapter = new CandidateAdapter(candidatesList, this::onCandidateSelected);
        rvCandidates.setAdapter(candidateAdapter);

        rvCandidatesExpanded.setLayoutManager(new GridLayoutManager(this, 5));
        expandedCandidateAdapter = new CandidateAdapter(candidatesList, this::onCandidateSelected);
        rvCandidatesExpanded.setAdapter(expandedCandidateAdapter);

        tvExpandCandidates.setOnClickListener(v -> toggleCandidateExpansion());

        root.findViewById(R.id.btn_globe).setOnClickListener(v -> switchKeyboardMode());
        root.findViewById(R.id.btn_mic).setOnClickListener(v -> startVoiceRecognition());

        engine = new BopomofoEngine(this);
        renderKeyboard();
        return root;
    }

    private void toggleCandidateExpansion() {
        isCandidateExpanded = !isCandidateExpanded;
        rvCandidatesExpanded.setVisibility(isCandidateExpanded ? View.VISIBLE : View.GONE);
        keyboardRowsContainer.setVisibility(isCandidateExpanded ? View.GONE : View.VISIBLE);
        tvExpandCandidates.setText(isCandidateExpanded ? "∧" : "∨");
    }

    private void switchKeyboardMode() {
        finishPendingComposing();
        clearComposing();
        if (currentMode == Mode.BOPOMOFO) {
            currentMode = Mode.ENGLISH;
        } else {
            currentMode = Mode.BOPOMOFO;
        }
        isPageB = false;
        if (isCandidateExpanded) toggleCandidateExpansion();
        renderKeyboard();
    }

    // 切換模式前，如果英文還打到一半（尚未送出），先把目前輸入框裡的底線組字文字正式定案，
    // 避免殘留一段沒有結束的組字狀態。
    private void finishPendingComposing() {
        if (currentMode == Mode.ENGLISH && composingCode.length() > 0) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
    }

    private void startVoiceRecognition() {
        // 麥克風權限沒開的話，IME 服務本身沒辦法跳系統授權視窗（那需要 Activity），
        // 這裡改成提示使用者去「設定」App 裡開權限。
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "請先在「iOS注音輸入法」設定頁開啟麥克風權限", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this, "這台裝置不支援語音輸入", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (isListening) {
            stopVoiceRecognition();
            return;
        }

        if (speechRecognizer == null) {
            speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { isListening = false; }
                @Override public void onError(int error) {
                    isListening = false;
                    updateMicIcon();
                }
                @Override public void onResults(android.os.Bundle results) {
                    isListening = false;
                    java.util.ArrayList<String> matches = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        commitTextDirectly(matches.get(0));
                    }
                    updateMicIcon();
                }
                @Override public void onPartialResults(android.os.Bundle partialResults) {}
                @Override public void onEvent(int eventType, android.os.Bundle params) {}
            });
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        speechRecognizer.startListening(intent);
        isListening = true;
        updateMicIcon();
    }

    private void stopVoiceRecognition() {
        if (speechRecognizer != null) speechRecognizer.stopListening();
        isListening = false;
        updateMicIcon();
    }

    private void updateMicIcon() {
        View root = getInputRootViewSafe();
        if (root == null) return;
        View micBtn = root.findViewById(R.id.btn_mic);
        if (micBtn != null) micBtn.setAlpha(isListening ? 0.4f : 1.0f);
    }

    private View cachedRootView;

    private View getInputRootViewSafe() {
        return cachedRootView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void renderKeyboard() {
        keyboardRowsContainer.removeAllViews();

        switch (currentMode) {
            case BOPOMOFO:
                if (!isPageB) {
                    addRow(ROW_A1, 46, 0.5f, 0.5f);   // 第一排：左右各留半格
                    addRow(ROW_A2, 46, 0f, 0f);
                    addRow(ROW_A3, 46, 0f, 0f);
                } else {
                    addRow(ROW_B1, 46, 0.5f, 0.5f);   // 第一排：左右各留半格
                    addRow(ROW_B2, 46, 1.0f, 0f);     // 第二排：左邊留一格
                    addToneRow(ROW_B3, 46);           // 第三排：Shift/Backspace貼齊邊界，聲調鍵各寬1.5格
                }
                addBottomRow(46, "123", true);
                break;
            case ENGLISH:
                addRow(ROW_EN1, 46, 0f, 0f);
                addRow(ROW_EN2, 46, 0f, 0f);
                addRow(ROW_EN3, 46, 0f, 0f);
                addBottomRow(46, "123", false);
                break;
            case SYMBOLS_1:
                addRow(ROW_NUM1, 46, 0f, 0f);
                addRow(isPageB ? ROW_NUM2_EN : ROW_NUM2_ZH, 46, 0f, 0f);
                addRow(isPageB ? ROW_NUM3_EN : ROW_NUM3_ZH, 46, 0f, 0f);
                addBottomRow(46, "ㄅㄆㄇ", false);
                break;
            case SYMBOLS_2:
                addRow(ROW_SYM1, 46, 0f, 0f);
                addRow(ROW_SYM2, 46, 0f, 0f);
                addRow(ROW_SYM3, 46, 0f, 0f);
                addBottomRow(46, "ㄅㄆㄇ", false);
                break;
            case EMOJI:
                renderEmojiView();
                break;
        }
    }

    private void renderEmojiView() {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(140)));
        RecyclerView rvEmoji = new RecyclerView(this);
        rvEmoji.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rvEmoji.setLayoutManager(new GridLayoutManager(this, 8));

        List<BopomofoEngine.CandidateItem> emojiItems = new ArrayList<>();
        for (String e : EMOJIS) {
            emojiItems.add(new BopomofoEngine.CandidateItem(e, 0));
        }

        rvEmoji.setAdapter(new CandidateAdapter(emojiItems, item -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(item.text, 1);
        }));
        row.addView(rvEmoji);
        keyboardRowsContainer.addView(row);
        addBottomRow(46, "ㄅㄆㄇ", false);
    }

    // leadingSpacerWeight / trailingSpacerWeight：兩側留白的「格數」，以一般按鍵的寬度(weight=1.0)為單位。
    // 例如 0.5f 代表留半格空白，1.0f 代表留一整格空白，0f 代表不留白（貼齊左右邊界）。
    private void addRow(String[] keys, int heightDp, float leadingSpacerWeight, float trailingSpacerWeight) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(heightDp)
        ));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dpToPx(2), 0, dpToPx(2));

        if (leadingSpacerWeight > 0) row.addView(makeSpacer(leadingSpacerWeight));
        for (String key : keys) {
            row.addView(createKeyButton(key, 1.0f));
        }
        if (trailingSpacerWeight > 0) row.addView(makeSpacer(trailingSpacerWeight));

        keyboardRowsContainer.addView(row);
    }

    // B頁第三排（聲調排）：Shift 在最左、Backspace 在最右，兩者都跟其他排的一般按鍵一樣寬(weight=1.0)，
    // 剛好對齊上面幾排的左右邊界；中間 4 個聲調鍵各寬 1.5 格，兩側各留 0.5 格空白再接聲調鍵。
    // 依照 ROW_B3 目前的定義，keys 陣列固定是 [PAGE_TOGGLE, 聲調x4, BACKSPACE] 共 6 個元素。
    private void addToneRow(String[] keys, int heightDp) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(heightDp)
        ));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dpToPx(2), 0, dpToPx(2));

        row.addView(createKeyButton(keys[0], 1.0f)); // Shift / PAGE_TOGGLE，靠最左
        row.addView(makeSpacer(0.5f));
        for (int i = 1; i <= 4; i++) {
            row.addView(createKeyButton(keys[i], 1.5f)); // 4 個聲調鍵，各寬 1.5 格
        }
        row.addView(makeSpacer(0.5f));
        row.addView(createKeyButton(keys[5], 1.0f)); // Backspace，靠最右

        keyboardRowsContainer.addView(row);
    }

    // 建立一個看不見的彈性空白區塊，用來在排的兩側留白
    private View makeSpacer(float weight) {
        View spacer = new View(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        spacer.setLayoutParams(sp);
        return spacer;
    }

    // 建立單顆按鍵，抽成共用方法讓一般排 (addRow) 跟聲調排 (addToneRow) 都能重複使用，
    // 差別只在於傳入的 weight（決定這顆鍵在排裡佔多寬）。
    private Button createKeyButton(String key, float weight) {
        Button btn = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        btn.setLayoutParams(lp);
        btn.setTextSize(19);
        btn.setTextColor(keyTextColor());
        btn.setPadding(0, 0, 0, 0);

        if ("PAGE_TOGGLE".equals(key)) {
            btn.setText(isPageB ? "⬆" : "⇧");
            btn.setTextSize(17);
            btn.setBackgroundResource(keyBgSpecialRes());
            setInstantPress(btn, () -> {
                isPageB = !isPageB;
                renderKeyboard();
            });
        } else if ("BACKSPACE".equals(key)) {
            btn.setText("⌫");
            btn.setTextSize(17);
            btn.setBackgroundResource(keyBgSpecialRes());
            setupBackspaceContinuousDelete(btn);
        } else if ("SHIFT".equals(key)) {
            btn.setText(isShift ? "⬆" : "⇧");
            btn.setTextSize(17);
            btn.setBackgroundResource(keyBgSpecialRes());
            setInstantPress(btn, () -> {
                isShift = !isShift;
                renderKeyboard();
            });
        } else if ("#+=".equals(key)) {
            btn.setText("#+=");
            btn.setTextSize(15);
            btn.setBackgroundResource(keyBgSpecialRes());
            setInstantPress(btn, () -> {
                currentMode = Mode.SYMBOLS_2;
                renderKeyboard();
            });
        } else if ("123".equals(key) && currentMode == Mode.SYMBOLS_2) {
            btn.setText("123");
            btn.setTextSize(15);
            btn.setBackgroundResource(keyBgSpecialRes());
            setInstantPress(btn, () -> {
                currentMode = Mode.SYMBOLS_1;
                renderKeyboard();
            });
        } else {
            String displayKey = (currentMode == Mode.ENGLISH && isShift) ? key.toUpperCase() : key;
            btn.setText(displayKey);
            btn.setBackgroundResource(keyBgNormalRes());
            setInstantPress(btn, () -> handleSymbol(displayKey));
        }
        return btn;
    }

    private void addBottomRow(int heightDp, String modeSwitchText, boolean isZh) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(heightDp)
        ));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dpToPx(2), 0, dpToPx(2));

        Button btn123 = createBottomKey(modeSwitchText, 1.3f, true);
        setInstantPress(btn123, () -> {
            finishPendingComposing();
            clearComposing();
            if ("123".equals(modeSwitchText)) {
                currentMode = Mode.SYMBOLS_1;
            } else {
                currentMode = Mode.BOPOMOFO;
            }
            renderKeyboard();
        });

        Button btnEmoji = createBottomKey(currentMode == Mode.EMOJI ? "ABC" : "😊", 1.0f, true);
        setInstantPress(btnEmoji, () -> {
            finishPendingComposing();
            clearComposing();
            currentMode = (currentMode == Mode.EMOJI) ? Mode.BOPOMOFO : Mode.EMOJI;
            renderKeyboard();
        });

        String spaceText = (currentMode == Mode.BOPOMOFO) ? (composingCode.length() > 0 ? "注" : "空白") : "space";
        Button btnSpace = createBottomKey(spaceText, 4.3f, false);
        setInstantPress(btnSpace, this::handleSpace);

        Button btnEnter = createBottomKey("⏎", 1.4f, true);
        setInstantPress(btnEnter, () -> {
            if (composingCode.length() > 0) {
                commitTextDirectly(composingCode.toString());
            } else {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
            }
        });

        row.addView(btn123);
        row.addView(btnEmoji);
        row.addView(btnSpace);
        row.addView(btnEnter);

        keyboardRowsContainer.addView(row);
    }

    // 按下瞬間立刻觸發（而非等放開），並用 setPressed 讓原本的按壓底色選擇器自動生效
    private void setInstantPress(Button btn, Runnable action) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    giveKeyFeedback();
                    action.run();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.performClick();
                    return true;
            }
            return false;
        });
        btn.setOnClickListener(v -> {}); // 保留給無障礙服務使用，實際動作已由 ACTION_DOWN 處理
    }

    private void setupBackspaceContinuousDelete(Button btn) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    deleteInterval = 100;
                    handleBackspace();
                    deleteHandler.postDelayed(deleteRunnable, 350);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    deleteHandler.removeCallbacks(deleteRunnable);
                    return true;
            }
            return false;
        });
    }

    private Button createBottomKey(String text, float weight, boolean special) {
        Button btn = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        btn.setLayoutParams(lp);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setTextColor(keyTextColor());
        btn.setBackgroundResource(special ? keyBgSpecialRes() : keyBgNormalRes());
        return btn;
    }

    private void handleSymbol(String symbol) {
        if (currentMode == Mode.ENGLISH) {
            composingCode.append(symbol);
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.setComposingText(composingCode.toString(), 1);
            updateEnglishCandidates();
            return;
        } else if (currentMode != Mode.BOPOMOFO) {
            commitTextDirectly(symbol);
            return;
        }

        composingCode.append(symbol);

        boolean isTone = symbol.matches("[ˊˇˋ˙]");
        if (!isPageB && !isTone) {
            isPageB = true;
            renderKeyboard();
        } else if (isTone) {
            isPageB = false;
            renderKeyboard();
        }
        updateCandidates();
        updateComposingPreview();
    }

    private void handleSpace() {
        if (currentMode == Mode.ENGLISH) {
            InputConnection ic = getCurrentInputConnection();
            if (composingCode.length() > 0) {
                if (!candidatesList.isEmpty()) {
                    onCandidateSelected(candidatesList.get(0));
                }
                if (ic != null) ic.commitText(" ", 1);
            } else if (ic != null) {
                ic.commitText(" ", 1);
            }
            return;
        }
        // 注音模式：
        // 1. 「目前這個音節」還沒按聲調鍵（視為第一聲）-> 用精確比對找出真正對得上
        //    第一聲讀音的字；如果前面還有已經連打完成的音節，一併用最佳猜測送出。
        //    注意：只能對「目前這個音節」做精確比對，不能對整段緩衝區比對，
        //    否則連打多字時會查不到東西、或誤把前面已完成的字一起吃掉。
        // 2. 已經按過聲調鍵 -> 直接選前綴比對出來、目前排最前面的候選字
        //    （連打時這會是整段的最佳猜測，讓使用者一次選走）。
        // 3. 完全沒有輸入中的注音 -> 插入真正的空白字元。
        if (composingCode.length() > 0) {
            String currentSyllable = currentSyllableSubstring();
            boolean hasTone = !currentSyllable.isEmpty()
                    && "ˊˇˋ˙".indexOf(currentSyllable.charAt(currentSyllable.length() - 1)) >= 0;
            if (!hasTone && !currentSyllable.isEmpty()) {
                List<BopomofoEngine.CandidateItem> exact = engine.getExactMatch(currentSyllable);
                if (!exact.isEmpty()) {
                    int precedingLen = composingCode.length() - currentSyllable.length();
                    String text = exact.get(0).text;
                    if (precedingLen > 0) {
                        text = engine.bestGuessForPrefix(composingCode.substring(0, precedingLen)) + text;
                    }
                    commitTextDirectly(text);
                    return;
                }
            }
            if (!candidatesList.isEmpty()) {
                onCandidateSelected(candidatesList.get(0));
            }
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(" ", 1);
        }
    }

    // 取得「目前正在輸入的這個音節」：從最後一個聲調符號之後開始算，
    // 如果整段都還沒按過聲調，就是整段緩衝區本身。
    private String currentSyllableSubstring() {
        String buf = composingCode.toString();
        int lastToneIdx = -1;
        for (int i = 0; i < buf.length(); i++) {
            if ("ˊˇˋ˙".indexOf(buf.charAt(i)) >= 0) lastToneIdx = i;
        }
        return buf.substring(lastToneIdx + 1);
    }

    private void handleBackspace() {
        if (composingCode.length() > 0) {
            composingCode.deleteCharAt(composingCode.length() - 1);
            if (composingCode.length() == 0) {
                isPageB = false;
                renderKeyboard();
            }
            if (currentMode == Mode.ENGLISH) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    if (composingCode.length() > 0) ic.setComposingText(composingCode.toString(), 1);
                    else ic.commitText("", 1);
                }
                updateEnglishCandidates();
            } else {
                updateCandidates();
                updateComposingPreview();
            }
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        }
    }

    private void updateCandidates() {
        candidatesList.clear();
        if (composingCode.length() > 0) {
            candidatesList.addAll(engine.getCandidates(composingCode.toString()));
        }
        candidateAdapter.notifyDataSetChanged();
        expandedCandidateAdapter.notifyDataSetChanged();
    }

    private void updateEnglishCandidates() {
        candidatesList.clear();
        if (composingCode.length() > 0) {
            candidatesList.add(new BopomofoEngine.CandidateItem(composingCode.toString(), composingCode.length()));
            candidatesList.addAll(engine.getEnglishCandidates(composingCode.toString()));
        }
        candidateAdapter.notifyDataSetChanged();
        expandedCandidateAdapter.notifyDataSetChanged();
    }

    private void commitTextDirectly(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
        clearComposing(false);
    }

    private void clearComposing() {
        clearComposing(false);
    }

    private void clearComposing(boolean isSelection) {
        composingCode.setLength(0);
        if (!isSelection) {
            candidatesList.clear();
        }
        candidateAdapter.notifyDataSetChanged();
        expandedCandidateAdapter.notifyDataSetChanged();
        updateComposingPreview();

        if (isCandidateExpanded) toggleCandidateExpansion();

        if (isPageB) {
            isPageB = false;
            renderKeyboard();
        }
    }

    private void onCandidateSelected(BopomofoEngine.CandidateItem item) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(item.text, 1);
        }

        // 選完字後自動收回下拉選單
        if (isCandidateExpanded) {
            toggleCandidateExpansion();
        }

        if (item.consumedLength > 0 && item.consumedLength < composingCode.length()) {
            // 連打：扣除已選取的注音字根，保留剩餘的注音繼續打
            composingCode.delete(0, item.consumedLength);
            updateCandidates();
            updateComposingPreview();
            isPageB = false;
            renderKeyboard();
        } else {
            // 完整選字：載入關聯聯想詞庫供接續選擇
            composingCode.setLength(0);
            candidatesList.clear();
            if (currentMode == Mode.BOPOMOFO) {
                candidatesList.addAll(engine.getAssociationWords(item.text));
            }
            candidateAdapter.notifyDataSetChanged();
            expandedCandidateAdapter.notifyDataSetChanged();
            updateComposingPreview();
            if (isPageB) {
                isPageB = false;
                renderKeyboard();
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateComposingPreview() {
        if (tvComposing == null) return;
        if (composingCode.length() > 0 && currentMode == Mode.BOPOMOFO) {
            tvComposing.setText(composingCode.toString());
            tvComposing.setVisibility(View.VISIBLE);
        } else {
            tvComposing.setVisibility(View.GONE);
        }
    }

    private static class CandidateAdapter extends RecyclerView.Adapter<CandidateAdapter.ViewHolder> {
        private final List<BopomofoEngine.CandidateItem> list;
        private final OnItemClick listener;
        static boolean darkMode = true;

        interface OnItemClick {
            void onClick(BopomofoEngine.CandidateItem item);
        }

        CandidateAdapter(List<BopomofoEngine.CandidateItem> list, OnItemClick listener) {
            this.list = list;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_candidate, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BopomofoEngine.CandidateItem item = list.get(position);
            holder.tv.setText(item.text);
            holder.tv.setTextColor(holder.tv.getContext().getColor(
                    darkMode ? R.color.ios_text_color : R.color.ios_text_color_light));
            holder.itemView.setOnClickListener(v -> listener.onClick(item));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tv;
            ViewHolder(View itemView) {
                super(itemView);
                tv = itemView.findViewById(R.id.tv_candidate);
            }
        }
    }
}
