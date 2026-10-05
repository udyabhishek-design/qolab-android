package dev.drytis.qolab;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * v1.5.35 — Shared native channel discussion surface.
 *
 * ONE implementation of the EXISTING channel messaging contract
 * (GET/POST /chat/channels/{id}/messages, replies eager-loaded one level)
 * used by BOTH The Lobby (Discussion tab) and the Department Room (Chat
 * tab). Verified against the web Department Room, which renders the same
 * endpoints via RoomDiscussion.tsx — no second, incompatible messaging
 * implementation exists on Android.
 *
 * Provides: feed, composer (corrected v1.5.32 postJson encoding), thread
 * dialog (parent + actual replies), skeleton, retry, empty state.
 * NOT provided (contract has none natively): reactions, realtime.
 */
public final class ChannelDiscussionView {

    private static final String TAG = "ChannelDiscussion";

    public interface SessionLost {
        /** Transport dead / 401 — host activity decides how to re-auth. */
        void onSessionLost();
    }

    private final AppCompatActivity host;
    private final QolabWebViewTransport transport;
    private final long channelId;
    private final SessionLost sessionLost;

    private LinearLayout messagesList;
    private ScrollView messagesScroll;
    private View skeleton, errorView, retryView, composer;
    private EditText composerInput;
    private TextView sendBtn;

    private List<LobbyDataClient.LobbyMessage> messages;
    private boolean loaded, sending;

    public ChannelDiscussionView(AppCompatActivity host, long channelId,
                                 View inflatedRoot, SessionLost sessionLost) {
        this.host = host;
        this.transport = QolabWebViewTransport.get();
        this.channelId = channelId;
        this.sessionLost = sessionLost;
        bind(inflatedRoot);
    }

    private void bind(View root) {
        messagesList = root.findViewById(R.id.channel_messages_list);
        messagesScroll = root.findViewById(R.id.channel_messages_scroll);
        skeleton = root.findViewById(R.id.channel_skeleton);
        errorView = root.findViewById(R.id.channel_error);
        retryView = root.findViewById(R.id.channel_retry);
        composer = root.findViewById(R.id.channel_composer);
        composerInput = root.findViewById(R.id.channel_composer_input);
        sendBtn = root.findViewById(R.id.channel_composer_send);
        sendBtn.setOnClickListener(v -> attemptSend());
        composerInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                boolean canSend = s.toString().trim().length() > 0 && !sending;
                sendBtn.setEnabled(canSend);
                sendBtn.setAlpha(canSend ? 1f : 0.45f);
            }
        });
        retryView.setOnClickListener(v -> load());
    }

    /** Fetches the feed if not already loaded (lazy tab entry). */
    public void loadIfNeeded() {
        if (!loaded) load();
    }

    public void load() {
        if (transport == null) {
            sessionLost.onSessionLost();
            return;
        }
        skeleton.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        retryView.setVisibility(View.GONE);
        transport.getJson("/chat/channels/" + channelId + "/messages?per_page=50", r ->
                host.runOnUiThread(() -> {
                    skeleton.setVisibility(View.GONE);
                    Log.d(TAG, "messages status=" + r.status + " bodyLen="
                            + (r.body == null ? -1 : r.body.length()));
                    if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD
                            || r.status == 401) {
                        sessionLost.onSessionLost();
                        return;
                    }
                    if (r.status != 200 || r.body == null) {
                        showError();
                        return;
                    }
                    messages = LobbyDataClient.parseMessages(r.body);
                    if (messages == null) {
                        showError();
                        return;
                    }
                    loaded = true;
                    render();
                }));
    }

    private void showError() {
        errorView.setVisibility(View.VISIBLE);
        retryView.setVisibility(View.VISIBLE);
    }

    private void render() {
        messagesList.removeAllViews();
        if (messages == null || messages.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText(R.string.lobby_discussion_empty);
            empty.setTextColor(host.getColor(R.color.qm_muted));
            empty.setTextSize(13);
            empty.setPadding(0, dp(12), 0, dp(12));
            messagesList.addView(empty);
            return;
        }
        for (LobbyDataClient.LobbyMessage m : messages) {
            messagesList.addView(messageRow(m, true));
        }
        messagesScroll.post(() -> messagesScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** Message card; interactive (opens thread) only when replies exist. */
    private View messageRow(LobbyDataClient.LobbyMessage m, boolean interactive) {
        View card = LayoutInflater.from(host)
                .inflate(R.layout.view_lobby_message, messagesList, false);
        ((TextView) card.findViewById(R.id.lobby_msg_sender))
                .setText(m.senderName != null ? m.senderName : "");
        String when = RoomFormat.formatIsoDate(m.createdAtIso);
        ((TextView) card.findViewById(R.id.lobby_msg_when)).setText(when != null ? when : "");
        ((TextView) card.findViewById(R.id.lobby_msg_body))
                .setText(m.body != null ? m.body : "");
        TextView thread = card.findViewById(R.id.lobby_msg_thread);
        if (!m.replies.isEmpty()) {
            thread.setVisibility(View.VISIBLE);
            thread.setText(m.replies.size() == 1
                    ? host.getString(R.string.lobby_thread_one_reply)
                    : String.format(Locale.getDefault(),
                        host.getString(R.string.lobby_thread_replies), m.replies.size()));
            if (interactive) card.setOnClickListener(v -> openThread(m));
        }
        return card;
    }

    /** Native thread panel: parent message + its actual replies. */
    private void openThread(LobbyDataClient.LobbyMessage parent) {
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(host);
        heading.setText(R.string.lobby_thread_heading);
        heading.setTextColor(host.getColor(R.color.qm_ink));
        heading.setTextSize(16);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(10));
        panel.addView(heading);

        panel.addView(messageRow(parent, false));
        for (LobbyDataClient.LobbyMessage r : parent.replies) {
            panel.addView(messageRow(r, false));
        }

        ScrollView scroller = new ScrollView(host);
        scroller.addView(panel);
        new AlertDialog.Builder(host)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ── Composer (existing POST contract, corrected transport) ─────────

    private void attemptSend() {
        if (sending || transport == null) return;
        String text = composerInput.getText().toString().trim();
        if (text.isEmpty()) return; // body-or-attachment rule: never post empty
        sending = true;
        sendBtn.setEnabled(false);
        sendBtn.setAlpha(0.45f);
        try {
            JSONObject payload = new JSONObject();
            payload.put("body", text);
            transport.postJson("/chat/channels/" + channelId + "/messages",
                    payload.toString(), r -> host.runOnUiThread(() -> {
                        sending = false;
                        sendBtn.setEnabled(true);
                        sendBtn.setAlpha(1f);
                        Log.d(TAG, "send status=" + r.status);
                        if (r.status == 201 || r.status == 200) {
                            composerInput.setText("");
                            load(); // refetch: message + replies arrive together
                        } else if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD
                                || r.status == 401) {
                            sessionLost.onSessionLost();
                        } else {
                            Toast.makeText(host, R.string.lobby_send_failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }));
        } catch (Exception e) {
            sending = false;
            sendBtn.setEnabled(true);
            sendBtn.setAlpha(1f);
            Toast.makeText(host, R.string.lobby_send_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return (int) (v * host.getResources().getDisplayMetrics().density + 0.5f);
    }
}
