package com.dya.sqlitebrowser.Adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.dya.sqlitebrowser.Database.QueryResult;
import com.dya.sqlitebrowser.R;

public class GridAdapter extends RecyclerView.Adapter<GridAdapter.VH> {

    public interface Listener {
        void onRowClick(int position);
    }

    private final QueryResult result;
    private final int colWidth;
    private final Listener listener;

    public GridAdapter(QueryResult result, int colWidth, Listener listener) {
        this.result = result;
        this.colWidth = colWidth;
        this.listener = listener;
    }

    public static TextView makeCell(Context ctx, int widthPx, boolean header) {
        TextView tv = new TextView(ctx);
        int p = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        tv.setLayoutParams(new LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setPadding(p, p, p, p);
        tv.setMaxLines(header ? 1 : 2);
        // Set custom font
        Typeface font = ResourcesCompat.getFont(ctx, R.font.charis_sil_regular);
        tv.setTypeface(font);

        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTextSize(13);
        if (header) tv.setTypeface(font, Typeface.BOLD);
        return tv;
    }

    static class VH extends RecyclerView.ViewHolder {
        LinearLayout row;

        VH(LinearLayout v) {
            super(v);
            row = v;
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < result.columns.length; i++) {
            row.addView(makeCell(parent.getContext(), colWidth, false));
        }
        return new VH(row);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        String[] data = result.rows.get(pos);
        for (int i = 0; i < h.row.getChildCount(); i++) {
            ((TextView) h.row.getChildAt(i)).setText(i < data.length ? data[i] : "");
        }
        h.row.setBackgroundColor(pos % 2 == 0 ? 0x1A888888 : 0x00000000);
        h.row.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && listener != null) listener.onRowClick(p);
        });
    }

    @Override
    public int getItemCount() {
        return result.rows.size();
    }
}