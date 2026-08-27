package com.custom.iosime;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

public class SettingsActivity extends Activity {

    public static final String PREFS_NAME = "ime_settings";
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_SOUND = "sound_enabled";
    public static final String KEY_VIBRATION = "vibration_enabled";

    private static final int REQ_RECORD_AUDIO = 1001;

    private SharedPreferences prefs;
    private TextView tvMicStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Switch swDarkMode = findViewById(R.id.sw_dark_mode);
        Switch swSound = findViewById(R.id.sw_sound);
        Switch swVibration = findViewById(R.id.sw_vibration);
        Button btnMicPermission = findViewById(R.id.btn_mic_permission);
        Button btnEnableIme = findViewById(R.id.btn_enable_ime);
        Button btnSelectIme = findViewById(R.id.btn_select_ime);
        tvMicStatus = findViewById(R.id.tv_mic_status);

        swDarkMode.setChecked(prefs.getBoolean(KEY_DARK_MODE, true));
        swSound.setChecked(prefs.getBoolean(KEY_SOUND, false));
        swVibration.setChecked(prefs.getBoolean(KEY_VIBRATION, true));

        swDarkMode.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                prefs.edit().putBoolean(KEY_DARK_MODE, isChecked).apply());
        swSound.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                prefs.edit().putBoolean(KEY_SOUND, isChecked).apply());
        swVibration.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                prefs.edit().putBoolean(KEY_VIBRATION, isChecked).apply());

        btnMicPermission.setOnClickListener(v -> requestMicPermission());

        btnEnableIme.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        btnSelectIme.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });

        updateMicStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateMicStatus();
    }

    private void updateMicStatus() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        tvMicStatus.setText(granted ? "麥克風權限：已開啟 ✓" : "麥克風權限：尚未開啟");
    }

    private void requestMicPermission() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            // 已經有權限了，帶使用者去系統設定頁確認 / 想關閉的話也在那裡關
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateMicStatus();
    }
}
