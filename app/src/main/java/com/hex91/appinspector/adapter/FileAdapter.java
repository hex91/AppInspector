package com.hex91.appinspector.adapter;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hex91.appinspector.R;
import com.hex91.appinspector.util.FileLister;
import com.hex91.appinspector.util.SplitArchive;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 文件列表适配器：目录 / APK / APKS-APKM-XAPK / 其它文件 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

    public interface OnFileClickListener {
        void onClick(FileLister.Item item);

        void onLongClick(FileLister.Item item);
    }

    private final Context ctx;
    private final OnFileClickListener listener;
    private List<FileLister.Item> data = new java.util.ArrayList<>();

    public FileAdapter(Context ctx, OnFileClickListener l) {
        this.ctx = ctx;
        this.listener = l;
    }

    public void setData(List<FileLister.Item> d) {
        this.data = d == null ? new java.util.ArrayList<>() : d;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final FileLister.Item it = data.get(position);
        h.name.setText(it.name);
        h.meta.setText(metaOf(it));

        int icon = R.drawable.ic_file;
        if (it.dir) {
            icon = R.drawable.ic_folder;
        } else if (SplitArchive.isSplitArchive(it.name)) {
            icon = R.drawable.ic_archive;
        } else if (it.name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            icon = R.drawable.ic_apk;
        }
        h.icon.setImageResource(icon);

        h.itemView.setOnClickListener(v -> listener.onClick(it));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(it);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private String metaOf(FileLister.Item it) {
        if (it.dir) return "目录";
        StringBuilder sb = new StringBuilder();
        sb.append(Formatter.formatFileSize(ctx, it.size));
        if (it.mtime > 0) {
            sb.append("  ·  ")
              .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                      .format(new Date(it.mtime)));
        }
        return sb.toString();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;

        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.ivIcon);
            name = v.findViewById(R.id.tvName);
            meta = v.findViewById(R.id.tvMeta);
        }
    }
}
