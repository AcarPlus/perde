package com.perde.launcher;

import android.content.Context;

/**
 * Renk teması. Panel, baloncuk, arka plan bulutu ve bokeh renkleri buradan gelir.
 * "Sade" tema sürekli hareketi kapatır (yalnız geçiş animasyonları kalır): en serin ve en hafif seçenek.
 */
final class Theme {
    final String id, name;
    final int base;                 // arka planın taban rengi
    final int[][] panel;            // [YouTube, Stremio, HDMI, USB] x {c1, c2}
    final int[][] bubble;           // [Wi-Fi, Bluetooth, Görüntü, Fan, Ayarlar] x {c1, c2}
    final int[] clouds;             // seçili panel rengine eşlik eden 3 bulut rengi
    final int[] bokeh;              // boşsa bokeh yok
    final float cloudAlpha;         // bulut yoğunluğu
    final boolean motion;           // false: sahneler, toz, dönen çerçeveler durur

    private Theme(String id, String name, int base, int[][] panel, int[][] bubble, int[] clouds, int[] bokeh,
                  float cloudAlpha, boolean motion) {
        this.id = id;
        this.name = name;
        this.base = base;
        this.panel = panel;
        this.bubble = bubble;
        this.clouds = clouds;
        this.bokeh = bokeh;
        this.cloudAlpha = cloudAlpha;
        this.motion = motion;
    }

    static final Theme[] ALL = {
            new Theme("renkli", "Renkli", 0xFF06061A,
                    new int[][]{{0xFFFF3B3B, 0xFF7A0A1E}, {0xFF8B5CF6, 0xFF2E1065}, {0xFF06B6D4, 0xFF064E3B}, {0xFFF59E0B, 0xFF7C2D12}},
                    new int[][]{{0xFF38BDF8, 0xFF1D4ED8}, {0xFFA78BFA, 0xFF5B21B6}, {0xFF5EEAD4, 0xFF0F766E},
                            {0xFFFDBA74, 0xFFDC2626}, {0xFFF472B6, 0xFF9D174D}},
                    new int[]{0xFF9333EA, 0xFF2563EB, 0xFFDB2777},
                    new int[]{0xFFF472B6, 0xFF60A5FA, 0xFFFBBF24, 0xFFA78BFA, 0xFF34D399, 0xFFF87171}, 1f, true),
            new Theme("okyanus", "Okyanus", 0xFF020617,
                    new int[][]{{0xFF38BDF8, 0xFF0C4A6E}, {0xFF818CF8, 0xFF1E1B4B}, {0xFF2DD4BF, 0xFF134E4A}, {0xFF60A5FA, 0xFF1E3A8A}},
                    new int[][]{{0xFF7DD3FC, 0xFF0369A1}, {0xFFA5B4FC, 0xFF3730A3}, {0xFF5EEAD4, 0xFF0F766E},
                            {0xFF67E8F9, 0xFF0E7490}, {0xFF93C5FD, 0xFF1E40AF}},
                    new int[]{0xFF1D4ED8, 0xFF0E7490, 0xFF4338CA},
                    new int[]{0xFF7DD3FC, 0xFF5EEAD4, 0xFFA5B4FC, 0xFFE0F2FE, 0xFF38BDF8, 0xFF99F6E4}, 1f, true),
            new Theme("gunbatimi", "Gün batımı", 0xFF140611,
                    new int[][]{{0xFFFB923C, 0xFF9A3412}, {0xFFF472B6, 0xFF831843}, {0xFFFBBF24, 0xFF92400E}, {0xFFF87171, 0xFF7F1D1D}},
                    new int[][]{{0xFFFDBA74, 0xFFC2410C}, {0xFFF9A8D4, 0xFFBE185D}, {0xFFFCD34D, 0xFFB45309},
                            {0xFFFCA5A5, 0xFFB91C1C}, {0xFFC4B5FD, 0xFF6D28D9}},
                    new int[]{0xFFBE185D, 0xFFEA580C, 0xFF7C3AED},
                    new int[]{0xFFFDBA74, 0xFFF9A8D4, 0xFFFDE68A, 0xFFFCA5A5, 0xFFC4B5FD, 0xFFFB923C}, 1f, true),
            new Theme("orman", "Orman", 0xFF020A06,
                    new int[][]{{0xFF4ADE80, 0xFF14532D}, {0xFFA3E635, 0xFF3F6212}, {0xFF2DD4BF, 0xFF115E59}, {0xFF34D399, 0xFF064E3B}},
                    new int[][]{{0xFF86EFAC, 0xFF15803D}, {0xFFBEF264, 0xFF4D7C0F}, {0xFF5EEAD4, 0xFF0F766E},
                            {0xFFFDE68A, 0xFFA16207}, {0xFF6EE7B7, 0xFF047857}},
                    new int[]{0xFF15803D, 0xFF0F766E, 0xFF65A30D},
                    new int[]{0xFF86EFAC, 0xFFBEF264, 0xFFFDE68A, 0xFF6EE7B7, 0xFFA7F3D0, 0xFFD9F99D}, 1f, true),
            new Theme("sade", "Sade (hareketsiz, en serin)", 0xFF050506,
                    new int[][]{{0xFF64748B, 0xFF1E293B}, {0xFF71717A, 0xFF27272A}, {0xFF6B7280, 0xFF1F2937}, {0xFF78716C, 0xFF1C1917}},
                    new int[][]{{0xFF9CA3AF, 0xFF374151}, {0xFF9CA3AF, 0xFF374151}, {0xFF9CA3AF, 0xFF374151},
                            {0xFF9CA3AF, 0xFF374151}, {0xFF9CA3AF, 0xFF374151}},
                    new int[]{0xFF334155, 0xFF1F2937, 0xFF3F3F46},
                    new int[0], 0.6f, false),
    };

    static Theme cur = ALL[0];

    static Theme byId(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return ALL[0];
    }

    static void load(Context c) {
        cur = byId(c.getSharedPreferences("perde", Context.MODE_PRIVATE).getString("tema", "renkli"));
    }

    static void save(Context c, Theme t) {
        cur = t;
        c.getSharedPreferences("perde", Context.MODE_PRIVATE).edit().putString("tema", t.id).apply();
    }
}
