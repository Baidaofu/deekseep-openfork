package com.dsmod.probe;

import android.R;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.dsmod.probe.localapi.ApiContract;
import com.dsmod.probe.localapi.KeepAliveService;
import com.dsmod.probe.localapi.LocalApi;
import com.dsmod.probe.localapi.LocalApiConfig;
import com.dsmod.probe.localapi.LocalApiStats;

final class LocalApiUi {

    private interface Toggler {
        void apply(boolean z);
    }

    private LocalApiUi() {
    }

    static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        LocalApi.initialize(activity);
        boolean isDark = DeekseepUi.isDark(activity);
        int i = isDark ? -15000803 : -657672;
        int i2 = isDark ? -14474458 : -1;
        int i3 = isDark ? -14013907 : -1;
        int i4 = isDark ? -986896 : -15066598;
        int i5 = isDark ? -5592401 : -8946814;
        int i6 = isDark ? -12961219 : -1118482;
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundColor(i);
        LinearLayout linearLayout2 = new LinearLayout(activity);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout2.setPadding(dp(activity, 8.0f), statusBarHeight(activity), dp(activity, 16.0f), 0);
        linearLayout2.setBackgroundColor(i2);
        linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(-1, dp(activity, 56.0f) + statusBarHeight(activity)));
        TextView text = text(activity, "‹", 28.0f, i4, false);
        text.setGravity(17);
        text.setPadding(dp(activity, 8.0f), 0, dp(activity, 8.0f), 0);
        text.setClickable(true);
        text.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.close(dialog, linearLayout);
            }
        });
        linearLayout2.addView(text, new LinearLayout.LayoutParams(-2, dp(activity, 40.0f)));
        TextView text2 = text(activity, UiLanguage.text(activity, "本地 API", "Local API"), 18.0f, i4, true);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        layoutParams.leftMargin = dp(activity, 8.0f);
        linearLayout2.addView(text2, layoutParams);
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        linearLayout.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        LinearLayout linearLayout3 = new LinearLayout(activity);
        linearLayout3.setOrientation(1);
        linearLayout3.setPadding(dp(activity, 16.0f), dp(activity, 16.0f), dp(activity, 16.0f), dp(activity, 28.0f));
        scrollView.addView(linearLayout3, new FrameLayout.LayoutParams(-1, -2));
        final Runnable[] runnableArr = new Runnable[1];
        LinearLayout card = card(activity, i3);
        linearLayout3.addView(card);
        Switch switchView = switchView(activity, isDark);
        switchView.setChecked(LocalApi.isRunning());
        card.addView(switchRow(activity, UiLanguage.text(activity, "启用本地 API", "Enable Local API"), UiLanguage.text(activity, "在手机上提供 OpenAI / Anthropic 兼容接口，默认只监听回环地址", "Serve OpenAI / Anthropic compatible endpoints, bound to loopback unless you widen it"), i4, i5, switchView));
        final TextView text3 = text(activity, "", 12.0f, i5, false);
        text3.setPadding(dp(activity, 16.0f), dp(activity, 4.0f), dp(activity, 16.0f), dp(activity, 14.0f));
        card.addView(text3);
        linearLayout3.addView(section(activity, UiLanguage.text(activity, "连接", "Connection"), i5));
        LinearLayout card2 = card(activity, i3);
        linearLayout3.addView(card2);
        final TextView[] textViewArr = new TextView[1];
        card2.addView(actionRow(activity, UiLanguage.text(activity, "协议模式", "Protocol"), ApiContract.PROTOCOL_ANTHROPIC.equals(LocalApiConfig.get().protocolMode) ? "Anthropic" : "OpenAI", i4, i5, new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.showProtocolPicker(activity, textViewArr, runnableArr[0]);
            }
        }, textViewArr));
        card2.addView(divider(activity, i6));
        final TextView[] textViewArr2 = new TextView[1];
        card2.addView(actionRow(activity, UiLanguage.text(activity, "端口", "Port"), String.valueOf(LocalApiConfig.get().port), i4, i5, new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.showPortPicker(activity, textViewArr2, runnableArr[0]);
            }
        }, textViewArr2));
        card2.addView(divider(activity, i6));
        final TextView[] textViewArr3 = new TextView[1];
        card2.addView(actionRow(activity, UiLanguage.text(activity, "API 密钥", "API key"), mask(LocalApiConfig.get().apiKey), i4, i5, new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.showKeyDialog(activity, textViewArr3, runnableArr[0]);
            }
        }, textViewArr3));
        card2.addView(divider(activity, i6));
        Switch switchView2 = switchView(activity, isDark);
        switchView2.setChecked(LocalApiConfig.get().https);
        card2.addView(switchRow(activity, UiLanguage.text(activity, "启用 HTTPS", "Enable HTTPS"), UiLanguage.text(activity, "使用每设备自签证书；客户端需信任该证书或忽略校验", "Uses a per device self signed certificate; clients must trust it"), i4, i5, switchView2));
        card2.addView(divider(activity, i6));
        Switch switchView3 = switchView(activity, isDark);
        switchView3.setChecked(LocalApiConfig.get().allowLan);
        card2.addView(switchRow(activity, UiLanguage.text(activity, "允许局域网访问", "Allow LAN access"), UiLanguage.text(activity, "关闭时只监听 127.0.0.1，仅本机可用；开启后同一网络内的任何设备只要拿到密钥就能使用你的账号额度", "Off means 127.0.0.1 only. On lets any device on the same network spend your account quota with the key"), i4, i5, switchView3));
        linearLayout3.addView(section(activity, UiLanguage.text(activity, "行为", "Behaviour"), i5));
        LinearLayout card3 = card(activity, i3);
        linearLayout3.addView(card3);
        Switch switchView4 = switchView(activity, isDark);
        switchView4.setChecked(LocalApiConfig.get().keepAliveNotification);
        card3.addView(switchRow(activity, UiLanguage.text(activity, "后台保活", "Keep alive"), UiLanguage.text(activity, "显示常驻通知，降低后台被系统回收的概率", "Shows a sticky notification so the listener survives in background"), i4, i5, switchView4));
        card3.addView(divider(activity, i6));
        Switch switchView5 = switchView(activity, isDark);
        switchView5.setChecked(LocalApiConfig.get().serialRequests);
        card3.addView(switchRow(activity, UiLanguage.text(activity, "串行请求", "Serial requests"), UiLanguage.text(activity, "一次只处理一个补全，避免并发打爆上游", "Handle one completion at a time instead of racing upstream"), i4, i5, switchView5));
        card3.addView(divider(activity, i6));
        Switch switchView6 = switchView(activity, isDark);
        switchView6.setChecked(LocalApiConfig.get().forceReasoning);
        card3.addView(switchRow(activity, UiLanguage.text(activity, "强制思考模式", "Force reasoning"), UiLanguage.text(activity, "始终按推理模型处理请求", "Always route requests through the reasoning path"), i4, i5, switchView6));
        card3.addView(divider(activity, i6));
        Switch switchView7 = switchView(activity, isDark);
        switchView7.setChecked(LocalApiConfig.get().longContextRelay);
        card3.addView(switchRow(activity, UiLanguage.text(activity, "长上下文中继", "Long context relay"), UiLanguage.text(activity, "超长输入改为分段中继，牺牲速度换取成功率", "Relay very long inputs in segments; slower but more reliable"), i4, i5, switchView7));
        card3.addView(divider(activity, i6));
        Switch switchView8 = switchView(activity, isDark);
        switchView8.setChecked(LocalApiConfig.get().antiCensor);
        card3.addView(switchRow(activity, UiLanguage.text(activity, "防审查", "Anti censor"), UiLanguage.text(activity, "对返回内容做一次去敏处理", "Post process responses to soften refusals"), i4, i5, switchView8));
        card3.addView(divider(activity, i6));
        Switch switchView9 = switchView(activity, isDark);
        switchView9.setChecked(LocalApiConfig.get().injectSystemPrompt);
        card3.addView(switchRow(activity, UiLanguage.text(activity, "注入系统提示词", "Inject system prompt"), UiLanguage.text(activity, "为每个补全请求附加一段固定系统提示", "Prepend a fixed system prompt to every completion"), i4, i5, switchView9));
        card3.addView(divider(activity, i6));
        final TextView[] textViewArr4 = new TextView[1];
        card3.addView(actionRow(activity, UiLanguage.text(activity, "系统提示词", "System prompt"), LocalApiConfig.get().systemPrompt, i4, i5, new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.showPromptDialog(activity, textViewArr4);
            }
        }, textViewArr4));
        linearLayout3.addView(section(activity, UiLanguage.text(activity, "诊断", "Diagnostics"), i5));
        LinearLayout card4 = card(activity, i3);
        linearLayout3.addView(card4);
        final TextView[] textViewArr5 = new TextView[1];
        card4.addView(actionRow(activity, UiLanguage.text(activity, "请求统计", "Request statistics"), "", i4, i5, new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.showStatsDialog(activity, textViewArr5);
            }
        }, textViewArr5));
        card4.addView(divider(activity, i6));
        card4.addView(actionRow(activity, UiLanguage.text(activity, "复制连接信息", "Copy connection details"), UiLanguage.text(activity, "把 Base URL 与密钥复制到剪贴板", "Copy the base URL and key to the clipboard"), i4, i5, new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.copy(activity, LocalApi.connectionCard(activity));
            }
        }, null));
        card4.addView(divider(activity, i6));
        card4.addView(actionRow(activity, UiLanguage.text(activity, "查看 API 日志", "View API log"), UiLanguage.text(activity, "最近数百条请求与错误记录", "The most recent request and error records"), i4, i5, new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LocalApiUi.showLogDialog(activity);
            }
        }, null));
        runnableArr[0] = new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                LocalApiConfig.State state = LocalApiConfig.get();
                text3.setText(LocalApiUi.endpointSummary(activity));
                textViewArr[0].setText(ApiContract.PROTOCOL_ANTHROPIC.equals(state.protocolMode) ? "Anthropic" : "OpenAI");
                textViewArr2[0].setText(String.valueOf(state.port));
                textViewArr3[0].setText(LocalApiUi.mask(state.apiKey));
                textViewArr5[0].setText(LocalApiStats.summary());
            }
        };
        switchView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            private boolean reverting;

            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                if (this.reverting) {
                    return;
                }
                if (z) {
                    try {
                        if (LocalApi.start(activity)) {
                            runnableArr[0].run();
                            return;
                        }
                    } catch (Throwable th) {
                        LocalApiUi.toast(activity, UiLanguage.text(activity, "启动失败：" + th, "Could not start: " + th));
                    }
                    this.reverting = true;
                    compoundButton.setChecked(!z);
                    this.reverting = false;
                    return;
                }
                LocalApi.stop();
                try {
                    activity.stopService(KeepAliveService.createIntent(activity));
                } catch (Throwable th2) {
                }
                runnableArr[0].run();
            }
        });
        switchView2.setOnCheckedChangeListener(restarting(activity, runnableArr[0], new Toggler() {
            @Override // com.dsmod.probe.LocalApiUi.Toggler
            public void apply(boolean z) {
                LocalApiConfig.setHttps(z);
            }
        }, switchView2));
        switchView3.setOnCheckedChangeListener(restarting(activity, runnableArr[0], new Toggler() {
            @Override // com.dsmod.probe.LocalApiUi.Toggler
            public void apply(boolean z) {
                LocalApiConfig.setAllowLan(z);
            }
        }, switchView3));
        switchView4.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                LocalApiUi.applyFlag("keepAliveNotification", z);
                LocalApi.onHostResumed(activity);
            }
        });
        switchView5.setOnCheckedChangeListener(simple("serialRequests", switchView5));
        switchView6.setOnCheckedChangeListener(simple("forceReasoning", switchView6));
        switchView7.setOnCheckedChangeListener(simple("longContextRelay", switchView7));
        switchView8.setOnCheckedChangeListener(simple("antiCensor", switchView8));
        switchView9.setOnCheckedChangeListener(simple("injectSystemPrompt", switchView9));
        runnableArr[0].run();
        UiLanguage.localizeTree(activity, linearLayout);
        dialog.setContentView(linearLayout);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setBackgroundDrawable(new ColorDrawable(i));
        }
        DeekseepUi.trackChildDialog(dialog);
        DeekseepUi.openWithSlide(dialog, linearLayout);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override // android.content.DialogInterface.OnKeyListener
            public boolean onKey(DialogInterface dialogInterface, int i7, KeyEvent keyEvent) {
                if (i7 == 4 && keyEvent.getAction() == 1) {
                    LocalApiUi.close(dialog, linearLayout);
                    return true;
                }
                return false;
            }
        });
    }

    private static CompoundButton.OnCheckedChangeListener simple(final String str, final CompoundButton compoundButton) {
        return new CompoundButton.OnCheckedChangeListener() {
            private boolean reverting;

            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton2, boolean z) {
                if (this.reverting) {
                    return;
                }
                LocalApiUi.applyFlag(str, z);
                if (LocalApiUi.flag(str) != z) {
                    this.reverting = true;
                    compoundButton.setChecked(true ^ z);
                    this.reverting = false;
                }
            }
        };
    }

    private static CompoundButton.OnCheckedChangeListener restarting(final Activity activity, final Runnable runnable, final Toggler toggler, final CompoundButton compoundButton) {
        return new CompoundButton.OnCheckedChangeListener() {
            private boolean reverting;

            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton2, boolean z) {
                if (this.reverting) {
                    return;
                }
                boolean isRunning = LocalApi.isRunning();
                toggler.apply(z);
                if (!isRunning || LocalApiUi.restart(activity)) {
                    runnable.run();
                    return;
                }
                toggler.apply(!z);
                this.reverting = true;
                compoundButton.setChecked(true ^ z);
                this.reverting = false;
            }
        };
    }

    public static void applyFlag(String str, boolean z) {
        if (!"enabled".equals(str)) {
            if (!"https".equals(str)) {
                if (!"allowLan".equals(str)) {
                    if (!"serialRequests".equals(str)) {
                        if (!"antiCensor".equals(str)) {
                            if ("injectSystemPrompt".equals(str)) {
                                LocalApiConfig.setInjectSystemPrompt(z);
                                return;
                            }
                            if ("longContextRelay".equals(str)) {
                                LocalApiConfig.setLongContextRelay(z);
                                return;
                            } else {
                                if (!"forceReasoning".equals(str)) {
                                    if ("keepAliveNotification".equals(str)) {
                                        LocalApiConfig.setKeepAliveNotification(z);
                                        return;
                                    }
                                    return;
                                }
                                LocalApiConfig.setForceReasoning(z);
                                return;
                            }
                        }
                        LocalApiConfig.setAntiCensor(z);
                        return;
                    }
                    LocalApiConfig.setSerialRequests(z);
                    return;
                }
                LocalApiConfig.setAllowLan(z);
                return;
            }
            LocalApiConfig.setHttps(z);
            return;
        }
        LocalApiConfig.setEnabled(z);
    }

    public static boolean flag(String str) {
        LocalApiConfig.State state = LocalApiConfig.get();
        if ("enabled".equals(str)) {
            return state.enabled;
        }
        if ("https".equals(str)) {
            return state.https;
        }
        if ("allowLan".equals(str)) {
            return state.allowLan;
        }
        if ("serialRequests".equals(str)) {
            return state.serialRequests;
        }
        if ("antiCensor".equals(str)) {
            return state.antiCensor;
        }
        if ("injectSystemPrompt".equals(str)) {
            return state.injectSystemPrompt;
        }
        if ("longContextRelay".equals(str)) {
            return state.longContextRelay;
        }
        if ("forceReasoning".equals(str)) {
            return state.forceReasoning;
        }
        if ("keepAliveNotification".equals(str)) {
            return state.keepAliveNotification;
        }
        return false;
    }

    public static boolean restart(Activity activity) {
        LocalApi.stop();
        try {
            return LocalApi.start(activity);
        } catch (Throwable th) {
            toast(activity, UiLanguage.text(activity, "重启监听失败：" + th, "Could not restart: " + th));
            return false;
        }
    }

    public static void showProtocolPicker(final Activity activity, final TextView[] textViewArr, final Runnable runnable) {
        final String[] strArr = {"OpenAI (/v1)", "Anthropic (/v1)"};
        new AlertDialog.Builder(activity).setTitle(UiLanguage.text(activity, "协议模式", "Protocol")).setItems(strArr, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                String str;
                boolean isRunning = LocalApi.isRunning();
                if (i == 1) {
                    str = ApiContract.PROTOCOL_ANTHROPIC;
                } else {
                    str = ApiContract.PROTOCOL_OPENAI;
                }
                LocalApiConfig.setProtocolMode(str);
                if (isRunning) {
                    LocalApiUi.restart(activity);
                }
                textViewArr[0].setText(strArr[i]);
                runnable.run();
            }
        }).setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), (DialogInterface.OnClickListener) null).show();
    }

    public static void showPortPicker(final Activity activity, final TextView[] textViewArr, final Runnable runnable) {
        final EditText editText = new EditText(activity);
        editText.setInputType(2);
        editText.setText(String.valueOf(LocalApiConfig.get().port));
        editText.setSingleLine(true);
        new AlertDialog.Builder(activity).setTitle(UiLanguage.text(activity, "端口", "Port")).setMessage(UiLanguage.text(activity, "取值范围 1024 - 65535", "Anywhere from 1024 to 65535")).setView(editText).setPositiveButton(UiLanguage.text(activity, "保存", "Save"), new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                int parsePort = LocalApiUi.parsePort(editText.getText());
                if (parsePort == 0) {
                    LocalApiUi.toast(activity, UiLanguage.text(activity, "端口无效", "Invalid port"));
                    return;
                }
                boolean isRunning = LocalApi.isRunning();
                LocalApiConfig.setPort(parsePort);
                if (isRunning && !LocalApiUi.restart(activity)) {
                    LocalApiConfig.setPort(LocalApiConfig.DEFAULT_PORT);
                }
                textViewArr[0].setText(String.valueOf(LocalApiConfig.get().port));
                runnable.run();
            }
        }).setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), (DialogInterface.OnClickListener) null).show();
    }

    public static int parsePort(CharSequence charSequence) {
        if (charSequence == null) {
            return 0;
        }
        try {
            int parseInt = Integer.parseInt(charSequence.toString().trim());
            if (parseInt < 1024 || parseInt > 65535) {
                return 0;
            }
            return parseInt;
        } catch (Throwable th) {
            return 0;
        }
    }

    public static void showKeyDialog(final Activity activity, final TextView[] textViewArr, final Runnable runnable) {
        String str = LocalApiConfig.get().apiKey;
        new AlertDialog.Builder(activity).setTitle(str).setItems(new String[]{UiLanguage.text(activity, "复制密钥", "Copy key"), UiLanguage.text(activity, "轮换密钥", "Rotate key"), UiLanguage.text(activity, "自定义密钥", "Set custom key")}, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                if (i == 0) {
                    LocalApiUi.copy(activity, LocalApiConfig.get().apiKey);
                } else {
                    if (i != 1) {
                        LocalApiUi.showCustomKeyDialog(activity, textViewArr, runnable);
                        return;
                    }
                    LocalApiConfig.rotateKey();
                    textViewArr[0].setText(LocalApiUi.mask(LocalApiConfig.get().apiKey));
                    runnable.run();
                }
            }
        }).setNegativeButton(UiLanguage.text(activity, "关闭", "Close"), (DialogInterface.OnClickListener) null).show();
    }

    public static void showCustomKeyDialog(final Activity activity, final TextView[] textViewArr, final Runnable runnable) {
        final EditText editText = new EditText(activity);
        editText.setSingleLine(true);
        editText.setText(LocalApiConfig.get().apiKey);
        new AlertDialog.Builder(activity).setTitle(UiLanguage.text(activity, "自定义密钥", "Set custom key")).setMessage(UiLanguage.text(activity, "8-256 位可打印字符，不含空格", "8 to 256 printable characters, no whitespace")).setView(editText).setPositiveButton(UiLanguage.text(activity, "保存", "Save"), new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                String customKey = LocalApiConfig.setCustomKey(editText.getText() == null ? "" : editText.getText().toString());
                if (customKey != null) {
                    LocalApiUi.toast(activity, UiLanguage.text(activity, "密钥无效：" + customKey, "Invalid key: " + customKey));
                } else {
                    textViewArr[0].setText(LocalApiUi.mask(LocalApiConfig.get().apiKey));
                    runnable.run();
                }
            }
        }).setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), (DialogInterface.OnClickListener) null).show();
    }

    public static void showPromptDialog(Activity activity, final TextView[] textViewArr) {
        final EditText editText = new EditText(activity);
        editText.setSingleLine(false);
        editText.setMinLines(5);
        editText.setGravity(48);
        editText.setText(LocalApiConfig.get().systemPrompt);
        new AlertDialog.Builder(activity).setTitle(UiLanguage.text(activity, "系统提示词", "System prompt")).setView(editText).setPositiveButton(UiLanguage.text(activity, "保存", "Save"), new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                LocalApiConfig.setSystemPrompt(editText.getText() == null ? "" : editText.getText().toString());
                textViewArr[0].setText(LocalApiConfig.get().systemPrompt);
            }
        }).setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), (DialogInterface.OnClickListener) null).show();
    }

    public static void showStatsDialog(Activity activity, final TextView[] textViewArr) {
        new AlertDialog.Builder(activity).setTitle(UiLanguage.text(activity, "请求统计", "Request statistics")).setMessage(LocalApiStats.summary()).setPositiveButton(UiLanguage.text(activity, "关闭", "Close"), (DialogInterface.OnClickListener) null).setNeutralButton(UiLanguage.text(activity, "清零", "Reset"), new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                LocalApiStats.reset();
                textViewArr[0].setText(LocalApiStats.summary());
            }
        }).show();
    }

    public static void showLogDialog(final Activity activity) {
        String recentLog = LocalApiStats.recentLog();
        if (recentLog == null || recentLog.length() == 0) {
            recentLog = UiLanguage.text(activity, "暂无记录", "No records yet");
        }
        new AlertDialog.Builder(activity).setTitle(UiLanguage.text(activity, "API 日志", "API log")).setMessage(recentLog).setPositiveButton(UiLanguage.text(activity, "关闭", "Close"), (DialogInterface.OnClickListener) null).setNeutralButton(UiLanguage.text(activity, "复制", "Copy"), new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                LocalApiUi.copy(activity, LocalApiStats.recentLog());
            }
        }).show();
    }

    public static String endpointSummary(Activity activity) {
        String text;
        StringBuilder sb = new StringBuilder();
        if (!LocalApi.isRunning()) {
            sb.append(UiLanguage.text(activity, "未运行", "Stopped"));
            return sb.toString();
        }
        String openAiBaseUrl = LocalApi.openAiBaseUrl();
        String anthropicBaseUrl = LocalApi.anthropicBaseUrl();
        boolean equals = ApiContract.PROTOCOL_ANTHROPIC.equals(LocalApiConfig.get().protocolMode);
        sb.append(UiLanguage.text(activity, "运行中", "Running"));
        if (LocalApiConfig.get().allowLan) {
            text = UiLanguage.text(activity, " · 局域网可访问", " · reachable on LAN");
        } else {
            text = UiLanguage.text(activity, " · 仅本机", " · this device only");
        }
        sb.append(text);
        sb.append('\n');
        if (equals) {
            if (anthropicBaseUrl != null) {
                sb.append(anthropicBaseUrl);
            }
        } else if (openAiBaseUrl != null) {
            sb.append(openAiBaseUrl);
        }
        return sb.toString();
    }

    public static String mask(String str) {
        return str == null ? "" : str.length() <= 10 ? str : str.substring(0, 6) + "…" + str.substring(str.length() - 4);
    }

    public static void copy(Context context, String str) {
        try {
            ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService("clipboard");
            if (clipboardManager != null) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("deekseep", str));
                toast(context, UiLanguage.text(context, "已复制", "Copied"));
                return;
            }
        } catch (Throwable th) {
        }
        toast(context, str);
    }

    public static void toast(Context context, String str) {
        try {
            Toast.makeText(context, str, 0).show();
        } catch (Throwable th) {
        }
    }

    private static View section(Context context, String str, int i) {
        TextView text = text(context, str, 13.0f, i, true);
        text.setPadding(dp(context, 6.0f), dp(context, 14.0f), dp(context, 6.0f), dp(context, 8.0f));
        return text;
    }

    private static View actionRow(Context context, String str, String str2, int i, int i2, View.OnClickListener onClickListener, TextView[] textViewArr) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(context, 16.0f), dp(context, 13.0f), dp(context, 16.0f), dp(context, 13.0f));
        linearLayout.addView(text(context, str, 15.0f, i, false));
        if (str2 == null) {
            str2 = "";
        }
        if (str2.length() > 60) {
            str2 = str2.substring(0, 57) + "…";
        }
        TextView text = text(context, str2, 12.0f, i2, false);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = dp(context, 3.0f);
        linearLayout.addView(text, layoutParams);
        if (textViewArr != null) {
            textViewArr[0] = text;
        }
        linearLayout.setClickable(true);
        linearLayout.setFocusable(true);
        linearLayout.setOnClickListener(onClickListener);
        return linearLayout;
    }

    private static View switchRow(Context context, String str, String str2, int i, int i2, Switch r11) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(context, 16.0f), dp(context, 13.0f), dp(context, 14.0f), dp(context, 13.0f));
        LinearLayout linearLayout2 = new LinearLayout(context);
        linearLayout2.setOrientation(1);
        linearLayout2.addView(text(context, str, 15.0f, i, false));
        if (str2 != null && str2.length() > 0) {
            TextView text = text(context, str2, 12.0f, i2, false);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.topMargin = dp(context, 3.0f);
            linearLayout2.addView(text, layoutParams);
        }
        linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout.addView(r11, new LinearLayout.LayoutParams(-2, -2));
        return linearLayout;
    }

    private static LinearLayout card(Context context, int i) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(1);
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(i);
        gradientDrawable.setCornerRadius(dp(context, 13.0f));
        linearLayout.setBackground(gradientDrawable);
        linearLayout.setElevation(dp(context, 1.0f));
        return linearLayout;
    }

    private static View divider(Context context, int i) {
        View view = new View(context);
        view.setBackgroundColor(i);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, dp(context, 1.0f));
        layoutParams.leftMargin = dp(context, 16.0f);
        view.setLayoutParams(layoutParams);
        return view;
    }

    private static Switch switchView(Context context, boolean z) {
        HubInsetSwitch hubInsetSwitch = new HubInsetSwitch(context);
        int[][] iArr = {new int[]{android.R.attr.state_checked}, new int[]{-16842912}};
        hubInsetSwitch.setThumbTintList(new ColorStateList(iArr, new int[]{-11703298, z ? -3355444 : -1}));
        hubInsetSwitch.setTrackTintList(new ColorStateList(iArr, new int[]{-5390337, z ? -11184811 : -4210753}));
        hubInsetSwitch.setBackground(null);
        return hubInsetSwitch;
    }

    private static TextView text(Context context, String str, float f, int i, boolean z) {
        TextView textView = new TextView(context);
        if (str == null) {
            str = "";
        }
        textView.setText(str);
        textView.setTextSize(2, f);
        textView.setTextColor(i);
        if (z) {
            textView.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return textView;
    }

    private static int statusBarHeight(Context context) {
        int identifier = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (identifier > 0) {
            return context.getResources().getDimensionPixelSize(identifier);
        }
        return 0;
    }

    private static int dp(Context context, float f) {
        return DeekseepUi.dp(context, f);
    }

    public static void close(Dialog dialog, View view) {
        DeekseepUi.slideOutAndDismiss(dialog, view);
    }
}
