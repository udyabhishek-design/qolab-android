package dev.drytis.qolab;

import android.view.View;
import android.view.ViewGroup;

/** Dump the measured view hierarchy of the login content with indentation. */
public class LoginHierarchyDump {
    public static void dump(View v, String indent) {
        if (v == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(v.getClass().getSimpleName());
        if (v.getId() != View.NO_ID) {
            sb.append(" #").append(v.getResources().getResourceEntryName(v.getId()));
        }
        sb.append(" top=").append(v.getTop()).append(" h=").append(v.getHeight());
        sb.append(" measH=").append(v.getMeasuredHeight());
        System.out.println(sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), indent + "  ");
        }
    }
}
