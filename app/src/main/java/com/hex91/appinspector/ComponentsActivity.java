package com.hex91.appinspector;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.hex91.appinspector.fragment.CompPageFragment;
import com.hex91.appinspector.model.CompInfo;
import com.hex91.appinspector.util.DumpCache;
import com.hex91.appinspector.util.PkgScanner;
import com.hex91.appinspector.util.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 四大组件详情页：Activity / Service / Receiver / Provider 分 Tab 展示 */
public class ComponentsActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_LABEL = "label";

    private static final String[] TAB_TITLES = {"Activity", "Service", "Receiver", "Provider"};

    private String pkg;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private int appliedTheme = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedTheme = Prefs.themeMode(this);
        Prefs.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_components);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        if (pkg == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(label == null ? pkg : label);
        toolbar.setSubtitle(pkg);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_components);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_export) {
                exportAll();
                return true;
            } else if (id == R.id.action_refresh) {
                PkgScanner.clearCache();
                DumpCache.clear();
                recreate();
                return true;
            }
            return false;
        });

        ViewPager2 pager = findViewById(R.id.viewPager);
        pager.setAdapter(new PagerAdapter(this, pkg));
        pager.setOffscreenPageLimit(3);

        TabLayout tabs = findViewById(R.id.tabLayout);
        new TabLayoutMediator(tabs, pager, (tab, position) -> {
            tab.setText(TAB_TITLES[position]);
        }).attach();
    }

    private static class PagerAdapter extends FragmentStateAdapter {
        private final String pkg;

        PagerAdapter(@NonNull FragmentActivity fa, String pkg) {
            super(fa);
            this.pkg = pkg;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return CompPageFragment.newInstance(pkg, position);
        }

        @Override
        public int getItemCount() {
            return 4;
        }
    }

    /** 导出全部组件为 txt 并分享 */
    private void exportAll() {
        executor.execute(() -> {
            @SuppressWarnings("unchecked")
            List<CompInfo>[] groups = PkgScanner.scanComponentsCached(this, pkg);
            StringBuilder sb = new StringBuilder();
            sb.append("包名：").append(pkg).append("\n\n");
            String[] names = {"Activity", "Service", "BroadcastReceiver", "ContentProvider"};
            for (int i = 0; i < 4; i++) {
                sb.append("===== ").append(names[i]).append(" (")
                  .append(groups[i].size()).append(") =====\n");
                for (CompInfo c : groups[i]) {
                    sb.append(c.name)
                      .append("  [exported=").append(c.exported)
                      .append(", enabled=").append(c.enabled).append("]");
                    if (c.permission != null && !c.permission.isEmpty()) {
                        sb.append("  permission=").append(c.permission);
                    }
                    if (c.filterSummary != null && !c.filterSummary.isEmpty()) {
                        sb.append("\n    ").append(c.filterSummary.replace("\n", "\n    "));
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
            final String content = sb.toString();
            runOnUiThread(() -> {
                try {
                    File dir = new File(getExternalFilesDir(null), "export");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir failed");
                    File f = new File(dir, pkg + "_components.txt");
                    FileOutputStream fos = new FileOutputStream(f);
                    OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                    osw.write(content);
                    osw.close();
                    Uri uri = FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", f);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "导出组件清单"));
                } catch (Exception e) {
                    // 分享失败时退化为文本展示
                    new AlertDialog.Builder(this)
                            .setTitle("组件清单（复制用）")
                            .setMessage(content.length() > 8000
                                    ? content.substring(0, 8000) + "\n…（已截断）" : content)
                            .setPositiveButton("关闭", null)
                            .setNeutralButton("复制全部", (d, w) -> {
                                ClipboardManager cm = (ClipboardManager)
                                        getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("components", content));
                                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .show();
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题设置页修改后返回时立即生效
        if (Prefs.themeMode(this) != appliedTheme) {
            appliedTheme = Prefs.themeMode(this);
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
