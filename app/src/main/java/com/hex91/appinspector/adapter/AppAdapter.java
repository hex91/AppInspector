package com.hex91.appinspector.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hex91.appinspector.R;
import com.hex91.appinspector.model.AppInfo;
import com.hex91.appinspector.util.IconCache;

import java.util.ArrayList;
import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    public interface OnItemClickListener {
        void onClick(AppInfo info);
        boolean onLongClick(AppInfo info);
    }

    private final Context ctx;
    private final List<AppInfo> data = new ArrayList<>();
    private OnItemClickListener listener;

    public AppAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setListener(OnItemClickListener l) {
        this.listener = l;
    }

    public void setData(List<AppInfo> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final AppInfo info = data.get(position);
        h.tvName.setText(info.label);
        h.tvPkg.setText(info.packageName);
        h.tvVersion.setText("v" + (info.versionName == null ? "?" : info.versionName)
                + "  (" + info.versionCode + ")  targetSdk " + info.targetSdk);

        if (info.isSystem) {
            h.tvBadge.setVisibility(View.VISIBLE);
            h.tvBadge.setText("系统");
            h.tvBadge.setBackgroundResource(R.drawable.bg_badge_system);
        } else {
            h.tvBadge.setVisibility(View.VISIBLE);
            h.tvBadge.setText("三方");
            h.tvBadge.setBackgroundResource(R.drawable.bg_badge_green);
        }
        if (!info.enabled) {
            h.tvBadge.setVisibility(View.VISIBLE);
            h.tvBadge.setText("已停用");
            h.tvBadge.setBackgroundResource(R.drawable.bg_badge_red);
        }

        Bitmap b = IconCache.get(ctx, info.packageName);
        if (b != null) h.ivIcon.setImageBitmap(b);
        else h.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onClick(info);
            }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return listener != null && listener.onLongClick(info);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName, tvPkg, tvVersion, tvBadge;

        Holder(View v) {
            super(v);
            ivIcon = v.findViewById(R.id.ivIcon);
            tvName = v.findViewById(R.id.tvName);
            tvPkg = v.findViewById(R.id.tvPkg);
            tvVersion = v.findViewById(R.id.tvVersion);
            tvBadge = v.findViewById(R.id.tvBadge);
        }
    }
}
