package com.dshmobile.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「手机控制」设置页：
 *   · 显示无障碍服务是否已开启，并提供跳转到系统设置的入口；
 *   · 列出已安装应用，勾选**允许 DSH 操作**的应用（白名单）。
 *
 * 白名单是唯一的授权边界：不在名单里的应用，agent 既看不到界面也点不动。
 */
public class AppControlActivity extends Activity {

    private TextView status;
    private TextView tip;
    private TextView shotStatus;
    private TextView shotButton;
    private TextView whitelistButton;
    private TextView micButton;
    private TextView quickAddButton;
    private static final int REQ_MIC = 0x4D43;   // 'MC'
    private android.widget.Switch guardSwitch;
    private android.widget.Switch backendSwitch;
    /** 界面开着时定时刷新守护状态（修复次数、最近一次时间会变）。 */
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * 看门狗平时由 DshService 拉起。这里再兜一次：
         * 万一用户是直接进本页（服务还没起来 / 被杀过），守护也得在。
         * start() 是幂等的，重复调用无副作用。
         */
        A11yGuard.start(this);

        /*
         * 版式照着 DSH 自己的插件页（DSH-IM / Agent 预设）学：
         * 深色底 + 圆角卡片 + 一行标题 + 最多一行说明。
         *
         * 用户的原话是这页"过多杂乱的解释"——所以这次把原来那些
         * "· 无障碍：快，但… · 直接命令：绕开…" 之类的长注释全部删掉，
         * 需要解释的放进卡片标题下面那一行小字里。
         */
        LinearLayout root = Ui.page(this, "手机控制", v -> finish());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        // 先把卡片按顺序摆好，后面各段逻辑只管往对应卡片里塞控件
        LinearLayout cA11y = Ui.card(this, "无障碍服务", "DSH 靠它读界面、点控件");
        root.addView(cA11y);
        LinearLayout cAuth = Ui.card(this, "一次性授权", "拿到它，无障碍被系统关掉才能自动补回来");
        root.addView(cAuth);
        LinearLayout cShot = Ui.card(this, "截图", "微信 / QQ 屏蔽控件树，只能靠截图看");
        root.addView(cShot);
        LinearLayout cBackend = Ui.card(this, "操作方式", "无障碍快；直接命令不依赖它，但读界面慢");
        root.addView(cBackend);
        LinearLayout cWhite = Ui.card(this, "应用白名单", "只允许 DSH 操作这里勾选的应用");
        root.addView(cWhite);
        LinearLayout cMic = Ui.card(this, "麦克风", "只给容器里的 DSH 网页用，不给外部网站");
        root.addView(cMic);

        status = Ui.status(this, "检查中…", Ui.SUB);
        cA11y.addView(status);

        cA11y.addView(Ui.button(this, "打开系统「无障碍」设置", false, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开设置: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));

        // ── 无障碍守护 ────────────────────────────────────────
        /*
         * 厂商（实测 vivo）会在 QQ / 微信切前台时把无障碍总开关置 0。
         * 拿到一次性授权后本 App 能自己写回去，这里就是那块控制面板。
         */
        guardSwitch = new android.widget.Switch(this);
        guardSwitch.setText("被系统关掉时自动补回来");
        guardSwitch.setTextSize(13f);
        guardSwitch.setPadding(0, pad / 2, 0, 0);
        guardSwitch.setChecked(Priv.guardEnabled(this));
        guardSwitch.setOnCheckedChangeListener((v, on) -> {
            Priv.setGuardEnabled(this, on);
            Toast.makeText(this, on
                    ? "已开启：无障碍被关掉会自动补回来"
                    : "已关闭：本 App 不会再碰无障碍设置", Toast.LENGTH_LONG).show();
        });
        cA11y.addView(guardSwitch);

        cAuth.addView(Ui.button(this, "用电脑授权（一条命令，永久有效）", false,
                v -> showGrantDialog()));

        cAuth.addView(Ui.button(this, "用 Shizuku 授权（不用电脑）", false,
                v -> ShizukuBridge.showGrantDialog(this, this::refreshStatus)));

        /*
         * 截屏：给 agent 一双"眼睛"。
         *
         * 微信/QQ 会屏蔽控件树，但屏幕像素可截 —— 这就是"看微信"的唯一途径。
         * 默认走无障碍截屏（零授权），无障碍被系统关掉时才需要这里的录屏授权兜底。
         */
        shotButton = new Button(this);
        shotButton.setOnClickListener(v -> {
            /*
             * 先解释一句再弹系统框。
             *
             * 原因：Android 15/16 的录屏授权框**默认选中的是「共享一个应用」**，
             * 而不是整个屏幕。用户如果直接点「继续」，我们就只能截到那一个应用 ——
             * 换了应用就黑屏，而且从界面上完全看不出哪里错了。
             * 这个默认值必须提前讲清楚。
             */
            new android.app.AlertDialog.Builder(this)
                    .setTitle("接下来请选「整个屏幕」")
                    .setMessage("系统会弹出录屏授权框。里面有一个「共享或录制的范围」，"
                            + "**默认是一个应用**，请把它改成：\n\n"
                            + "　　共享整个屏幕\n\n"
                            + "否则只能截到你选的那一个应用，切到别的应用就截不到了。\n\n"
                            + "（这个授权只用于给 AI 看屏幕内容，不会保存视频。）")
                    .setPositiveButton("好，去授权", (d, w) -> {
                        android.content.Intent it = Screenshot.consentIntent(this);
                        if (it == null) {
                            Toast.makeText(this, "本机不支持录屏截屏", Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            startActivityForResult(it, Screenshot.REQ_PROJECTION);
                        } catch (Throwable t) {
                            Toast.makeText(this, "打不开录屏授权: " + t, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        cShot.addView(shotButton);

        shotStatus = Ui.status(this, "检查中…", Ui.SUB);
        cShot.addView(shotStatus);

        // ── 操作方式（用户自己选） ─────────────────────────────
        /*
         * 两条路各有取舍，所以让用户选，而不是我们替他决定：
         *   无障碍：快，但可能被系统/厂商关掉（本 App 会自己补回来）
         *   直接命令：绕开无障碍，被关掉也能用，但读界面慢得多，且只能输 ASCII
         */
        backendSwitch = new android.widget.Switch(this);
        backendSwitch.setText("用直接命令操作（Shizuku，不依赖无障碍）");
        backendSwitch.setTextSize(13f);
        backendSwitch.setPadding(0, pad / 2, 0, 0);
        backendSwitch.setChecked(ShellControl.MODE_SHELL.equals(ShellControl.mode(this)));
        backendSwitch.setOnCheckedChangeListener((v, on) -> {
            ShellControl.setMode(this, on ? ShellControl.MODE_SHELL : ShellControl.MODE_A11Y);
            if (on && !ShellControl.available()) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Shizuku 现在不可用")
                        .setMessage("已切换为「直接命令」，但 Shizuku 当前没在运行，"
                                + "所以暂时会自动退回无障碍。\n\n"
                                + "当前状态：" + ShizukuBridge.statusText(this) + "\n\n"
                                + "启动 Shizuku 后这条通道就会自动生效。")
                        .setPositiveButton("知道了", null)
                        .show();
            } else {
                Toast.makeText(this, on
                        ? "已切换为直接命令（每次读界面会慢一些）"
                        : "已切换回无障碍服务", Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        });
        cBackend.addView(backendSwitch);

        /*
         * 白名单**不再挤在这一页**。
         *
         * 原来白名单和上面这一堆开关同页，按钮越加越多，应用列表被压成一小条 ——
         * 而这页真正要干的事就是"从一长串应用里挑几个"。现在它有自己的整屏页面
         * （{@link WhitelistActivity}），DSH 设置里也有独立入口。
         * 这里只留一个跳转按钮，顺便显示当前选了几个，免得用户找不到。
         */
        whitelistButton = Ui.button(this, "管理白名单", false, v -> openWhitelist());
        cWhite.addView(whitelistButton);
        // 一键把所有已安装应用加进来（用户明确要求的功能）
        cWhite.addView(Ui.button(this, "一键添加所有应用", false, v -> confirmAddAll()));

        /*
         * 麦克风：**故意不自动申请**。
         *
         * 用户要的是"别一装上就能听我说话"，所以这里只放一个入口：
         * 谁需要（网页要跑本地语音识别）谁自己点。
         * 平时网页请求麦克风时 MainActivity.onPermissionRequest 也会弹框，
         * 那个入口对用户来说太隐蔽（要看网页什么时候请求），
         * 所以再给一个能自己掌控的地方。
         */
        micButton = Ui.button(this, "麦克风权限", false, v -> {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 已经给了：系统不会再弹框，只能把它领到应用详情页让用户自己关
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(this, "无法打开系统设置: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
                return;
            }
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        });
        cMic.addView(micButton);

        /*
         * 白名单一键添加（手动那条路）。
         *
         * 不能取"当前前台" —— 用户正看着本页，前台就是我们自己。
         * 所以取**上一个不是本 App 的前台应用**，也就是"你刚才在用的那个"。
         */
        quickAddButton = Ui.button(this, "把刚才那个应用加入白名单", false, v -> {
            String pkg = DshAccessibilityService.lastOtherPackage();
            if (pkg == null || pkg.isEmpty()) {
                Toast.makeText(this, "还没看到过别的应用在前台 —— 先切到目标应用再回来点一次",
                        Toast.LENGTH_LONG).show();
                return;
            }
            java.util.Set<String> s = new java.util.LinkedHashSet<>(PhoneBridge.allowed(this));
            boolean added = s.add(pkg);
            PhoneBridge.setAllowed(this, s);
            QuickAdd.clear(this);
            Toast.makeText(this, added
                            ? "已把「" + QuickAdd.label(this, pkg) + "」加入白名单"
                            : "「" + QuickAdd.label(this, pkg) + "」本来就在白名单里",
                    Toast.LENGTH_LONG).show();
            refreshStatus();
        });
        cWhite.addView(quickAddButton);

        /*
         * ⚠️ 必须赋值给**字段** tip，不能写成 `TextView tip = new TextView(this)`。
         * 那样会声明一个同名的**局部变量**把字段遮住，字段一直是 null，
         * 于是 refreshStatus() 里 tip.setText(...) 直接 NPE ——
         * 表现是"一打开手机控制就闪退回桌面"，而编译器完全不会提醒。
         * （实测日志：Unable to resume AppControlActivity: NullPointerException
         *   at refreshStatus(AppControlActivity.java:281)）
         */
        tip = Ui.status(this, "", Ui.SUB);
        root.addView(tip);
    }

    /**
     * 一键把所有已安装应用加入白名单。
     *
     * <p>⚠️ 这会**取消安全边界** —— 白名单是"只允许 DSH 操作这些应用"的唯一约束，
     * 全加进去等于没有约束。所以必须先弹窗把后果讲清楚，不能点了就做。
     */
    private void confirmAddAll() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("把所有应用加入白名单？")
                .setMessage("这会把手机上所有已安装的应用都加进白名单，"
                        + "也就是 DSH 之后可以读取和操作任何一个应用。\n\n"
                        + "白名单是唯一的安全边界，这么做等于把它取消掉。\n\n"
                        + "如果只是想让 DSH 操作某几个应用，建议用「管理白名单」逐个勾选。")
                .setPositiveButton("我确定，全部添加", (d, w) -> addAllApps())
                .setNegativeButton("取消", null)
                .show();
    }

    private void addAllApps() {
        new Thread(() -> {
            final java.util.Set<String> s =
                    new java.util.LinkedHashSet<>(PhoneBridge.allowed(this));
            final int before = s.size();
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    s.add(ri.activityInfo.packageName);
                }
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "读取应用列表失败: " + t,
                        Toast.LENGTH_LONG).show());
                return;
            }
            PhoneBridge.setAllowed(this, s);
            runOnUiThread(() -> {
                Toast.makeText(this,
                        "已加入 " + (s.size() - before) + " 个应用（白名单现在 " + s.size() + " 个）",
                        Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        }, "dsh-add-all-apps").start();
    }

    private void openWhitelist() {
        try {
            startActivity(new android.content.Intent(this, WhitelistActivity.class));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开白名单页: " + t, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到本页时立刻检查一次（用户可能刚从系统设置里改完开关）
        A11yGuard.nudge();
        refreshStatus();
        ticker.removeCallbacks(refreshTick);
        ticker.postDelayed(refreshTick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(refreshTick);
    }

    /** 录屏授权结果转发给截图能力层。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Screenshot.REQ_PROJECTION) {
            boolean ok = resultCode == RESULT_OK
                    && Screenshot.onConsentResult(this, resultCode, data);
            Toast.makeText(this, ok
                    ? "录屏截屏已就绪 —— 无障碍被关掉时也能截图"
                    : "未开启录屏截屏（不影响无障碍截屏）",
                    Toast.LENGTH_LONG).show();
            refreshStatus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        boolean ok = grantResults != null && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, ok
                ? "麦克风已授权 —— 现在容器里的 DSH 页面可以做本地语音识别了"
                : "没有拿到麦克风权限（语音识别用不了，其它功能不受影响）",
                Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean on = DshAccessibilityService.isRunning();
        status.setText(A11yGuard.describe(this));
        status.setTextColor(on ? Color.parseColor("#4CAF50") : Color.parseColor("#FF7043"));

        if (guardSwitch != null) {
            boolean want = Priv.guardEnabled(this);
            // 只在用户真的改了才回调，否则会把 setChecked 的监听器再触发一遍
            if (guardSwitch.isChecked() != want) {
                guardSwitch.setOnCheckedChangeListener(null);
                guardSwitch.setChecked(want);
                guardSwitch.setOnCheckedChangeListener((v, checked) ->
                        Priv.setGuardEnabled(this, checked));
            }
        }

        // 底部只留"还没授权"这一条真正需要用户知道的信息；不需要就整行不占地方
        boolean canHeal = Priv.canHeal(this);
        tip.setText(canHeal ? "" : "还没做一次性授权 —— 无障碍被系统关掉后无法自动补回。");
        tip.setVisibility(canHeal ? android.view.View.GONE : android.view.View.VISIBLE);

        if (shotStatus != null) {
            boolean a11y = Screenshot.a11yAvailable();
            boolean proj = Screenshot.projectionAlive();
            shotStatus.setText(Screenshot.describe(this));
            shotStatus.setTextColor(a11y || proj
                    ? Color.parseColor("#4CAF50") : Color.parseColor("#FF7043"));
            if (shotButton != null) {
                // 无障碍那条路可用时，录屏只是兜底
                shotButton.setText(a11y ? "开启录屏截屏（兜底）" : "开启录屏截屏");
            }
        }

        // 白名单：这里只报"选了几个"，名单本身在专门那页
        if (whitelistButton != null) {
            int n = PhoneBridge.allowed(this).size();
            whitelistButton.setText("管理白名单（已选 " + n + " 个）");
        }

        if (quickAddButton != null) {
            String last = DshAccessibilityService.lastOtherPackage();
            quickAddButton.setText(last == null
                    ? "把刚才那个应用加入白名单"
                    : "把「" + QuickAdd.label(this, last) + "」加入白名单");
        }

        if (micButton != null) {
            boolean mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            micButton.setText(mic ? "麦克风：已授予（点这里去系统设置关掉）" : "麦克风：未授予 · 点这里授权");
        }
    }

    /** 展示那条一次性的授权命令，并支持一键复制。 */
    private void showGrantDialog() {
        String cmd = Priv.grantCommand(this);
        String msg = "把手机用数据线连到电脑，在电脑上执行这一条命令：\n\n"
                + cmd + "\n\n"
                + "只有这一次需要电脑。授权结果保存在系统里，"
                + "重启手机、更新 App 都不会掉。\n\n"
                + "如果没有电脑：装一个 Shizuku（Android 11 以上可以全程在手机上完成），"
                + "然后点「用 Shizuku 授权」。";

        new android.app.AlertDialog.Builder(this)
                .setTitle("一次性授权")
                .setMessage(msg)
                .setPositiveButton("复制命令", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-grant", cmd));
                    Toast.makeText(this, "已复制，粘到电脑终端里执行", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("检查一下", (d, w) -> {
                    refreshStatus();
                    Toast.makeText(this, Priv.canHeal(this) ? "已授权" : "还没授权",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}
