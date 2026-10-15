package com.hex91.appinspector.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hex91.appinspector.R;
import com.hex91.appinspector.adapter.CompAdapter;
import com.hex91.appinspector.model.CompInfo;
import com.hex91.appinspector.util.DumpCache;
import com.hex91.appinspector.util.Launcher;
import com.hex91.appinspector.util.PkgScanner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 某一类组件的列表页 */
public class CompPageFragment extends Fragment {

    private static final String ARG_PKG = "pkg";
    private static final String ARG_TYPE = "type";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private String pkg;
    private int type;
    private RecyclerView rv;
    private TextView tvEmpty;

    public static CompPageFragment newInstance(String pkg, int type) {
        CompPageFragment f = new CompPageFragment();
        Bundle b = new Bundle();
        b.putString(ARG_PKG, pkg);
        b.putInt(ARG_TYPE, type);
        f.setArguments(b);
        return f;
    }

    public CompPageFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            pkg = getArguments().getString(ARG_PKG);
            type = getArguments().getInt(ARG_TYPE);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        View v = inflater.inflate(R.layout.fragment_comp_list, container, false);
        rv = v.findViewById(R.id.rv);
        tvEmpty = v.findViewById(R.id.tvEmpty);
        CompAdapter compAdapter = new CompAdapter(requireContext());
        compAdapter.setCallback(new CompAdapter.Callback() {
            @Override
            public void onClick(CompInfo c) {
                Launcher.show(requireActivity(), c);
            }

            @Override
            public void onLongClick(CompInfo c) {
                showDetail(c);
            }
        });
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(compAdapter);
        load(compAdapter);
        return v;
    }

    private void load(final CompAdapter compAdapter) {
        POOL.execute(() -> {
            @SuppressWarnings("unchecked")
            List<CompInfo>[] groups = PkgScanner.scanComponentsCached(requireContext(), pkg);
            final List<CompInfo> list = groups[type];
            // intent-filter 需要 root + dumpsys，可能耗时，放在同一后台线程
            Map<String, String> filters = DumpCache.get(pkg);
            if (!filters.isEmpty()) {
                for (CompInfo c : list) {
                    String f = filters.get(c.name);
                    if (!TextUtils.isEmpty(f)) c.filterSummary = f;
                }
            }
            HANDLER.post(() -> {
                if (!isAdded()) return;
                compAdapter.setData(list);
                tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void showDetail(final CompInfo c) {
        new AlertDialog.Builder(requireContext())
                .setTitle(c.typeName() + " 详情")
                .setMessage(c.detail())
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制类名", (d, w) -> copy(c.name))
                .setNegativeButton("启动/调用", (d, w) -> Launcher.show(requireActivity(), c))
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", text));
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show();
    }
}
