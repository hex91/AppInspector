package com.hex91.appinspector.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

/** 应用图标缓存，避免列表滚动时反复跨进程取图标 */
public class IconCache {

    private static final int SIZE_DP = 48;
    private static LruCache<String, Bitmap> sCache;
    private static int sPx = 96;

    private static synchronized LruCache<String, Bitmap> cache(Context ctx) {
        if (sCache == null) {
            int maxKb = 8 * 1024; // 8MB
            sCache = new LruCache<String, Bitmap>(maxKb) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
            float density = ctx.getResources().getDisplayMetrics().density;
            sPx = (int) (SIZE_DP * density);
        }
        return sCache;
    }

    public static Bitmap get(Context ctx, String pkg) {
        LruCache<String, Bitmap> c = cache(ctx);
        Bitmap b = c.get(pkg);
        if (b != null) return b;
        Bitmap gen = load(ctx, pkg);
        if (gen != null) c.put(pkg, gen);
        return gen;
    }

    public static void warmUp(Context ctx, String pkg) {
        get(ctx, pkg);
    }

    private static Bitmap load(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            return toBitmap(d, sPx);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap toBitmap(Drawable drawable, int size) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) drawable).getBitmap();
            if (b.getWidth() == size && b.getHeight() == size) return b;
        }
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        // 自适应图标自带遮罩，这里不再二次裁剪
        if (Build.VERSION.SDK_INT >= 26 && drawable instanceof AdaptiveIconDrawable) {
            return bmp;
        }
        return bmp;
    }
}
