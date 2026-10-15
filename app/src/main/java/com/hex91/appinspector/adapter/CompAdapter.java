package com.hex91.appinspector.adapter;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hex91.appinspector.R;
import com.hex91.appinspector.model.CompInfo;

import java.util.ArrayList;
import java.util.List;

public class CompAdapter extends RecyclerView.Adapter<CompAdapter.Holder> {

    public interface Callback {
        void onClick(CompInfo c);
        void onLongClick(CompInfo c);
    }

    private final Context ctx;
    private final List<CompInfo> data = new ArrayList<>();
    private Callback cb;

    public CompAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setCallback(Callback c) {
        this.cb = c;
    }

    public void setData(List<CompInfo> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_component, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        final CompInfo c = data.get(pos);

        String shortName = c.shortName;
        if (!shortName.startsWith(".") && shortName.indexOf('.') < 0) shortName = "." + shortName;
        h.tvSimple.setText(shortName);

        h.tvFull.setText(c.name);

        if (c.exported) {
            h.tvExported.setText("已导出");
            h.tvExported.setBackgroundResource(R.drawable.bg_badge_green);
        } else {
            h.tvExported.setText("未导出");
            h.tvExported.setBackgroundResource(R.drawable.bg_badge_red);
        }

        StringBuilder meta = new StringBuilder();
        if (!c.enabled) meta.append("已停用  ");
        if (!TextUtils.isEmpty(c.permission)) meta.append("权限:").append(c.permission).append("  ");
        if (!TextUtils.isEmpty(c.processName) && !c.processName.equals(c.pkg)) {
            meta.append("进程:").append(c.processName).append("  ");
        }
        if (!TextUtils.isEmpty(c.launchMode) && !"standard".equals(c.launchMode)) {
            meta.append("启动模式:").append(c.launchMode).append("  ");
        }
        if (!TextUtils.isEmpty(c.authority)) meta.append("authority:").append(c.authority).append("  ");
        if (meta.length() > 0) {
            h.tvMeta.setVisibility(View.VISIBLE);
            h.tvMeta.setText(meta.toString().trim());
        } else {
            h.tvMeta.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(c.filterSummary)) {
            h.tvFilter.setVisibility(View.VISIBLE);
            h.tvFilter.setText(c.filterSummary);
        } else {
            h.tvFilter.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cb != null) cb.onClick(c);
            }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (cb != null) cb.onLongClick(c);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView tvSimple, tvFull, tvExported, tvMeta, tvFilter;

        Holder(View v) {
            super(v);
            tvSimple = v.findViewById(R.id.tvSimpleName);
            tvFull = v.findViewById(R.id.tvFullName);
            tvExported = v.findViewById(R.id.tvExported);
            tvMeta = v.findViewById(R.id.tvMeta);
            tvFilter = v.findViewById(R.id.tvFilter);
        }
    }
}
