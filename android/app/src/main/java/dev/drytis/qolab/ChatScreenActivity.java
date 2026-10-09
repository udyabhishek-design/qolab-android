package dev.drytis.qolab;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.5.39 — Native Chat: one conversation.
 *
 * History: GET /chat/channels/{id}/messages?per_page=50&page=1 (existing
 * endpoint, newest-first → reversed to chronological).
 * Send: POST /chat/channels/{id}/messages {body} → 201 {message}; a failed
 * send keeps the text in the composer and shows a retry chip — the message
 * is never silently dropped or double-sent (single-flight + id dedupe).
 * Realtime: private-channel.{id} .message.sent — skips our own sends
 * (matched by user_id), appends others; incoming while scrolled up does
 * NOT steal the scroll position.
 * Read receipt: PATCH /chat/channels/{id}/read after history load and
 * after new incoming messages while open (non-blocking, errors ignored).
 */
public class ChatScreenActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID = "chat_channel_id";
    public static final String EXTRA_CHANNEL_NAME = "chat_channel_name";
    public static final String EXTRA_CHANNEL_TYPE = "chat_channel_type";
    public static final String EXTRA_MY_USER_ID = "chat_my_user_id";

    private long channelId;
    private String channelName;
    private long myUserId;
    private boolean isGroup;

    private LinearLayout messagesList;
    private ScrollView messagesScroll;
    private View skeleton, errorBox, empty;
    private EditText composerInput;
    private TextView sendBtn, titleView, subtitleView;

    private final List<ChatDataClient.ChatMessage> messages = new ArrayList<>();
    private volatile boolean loading = false;
    private volatile boolean sending = false;

    /** v1.5.41: automatic network recovery handle. */
    private Object netWatcher;
    /** True once the user has scrolled at least one screen up. */
    private boolean userScrolledUp = false;

    private final ChatRealtime.Listener realtime = (chanId, event, data) -> {
        if (chanId != channelId) return;
        if (!".message.sent".equals(event)) return;
        ChatDataClient.ChatMessage m = ChatDataClient.parseBroadcastMessage(data);
        if (m == null || m.id <= 0) return;
        runOnUiThread(() -> {
            if (m.userId == myUserId) return; // own echo — already appended
            if (containsMessage(m.id)) return; // dedupe by id
            appendMessage(m);
            if (!userScrolledUp) scrollToBottom();
            markRead(m.id);
        });
    };

    /** v1.5.41: wraps `realtime` and adds reconnect handling — the socket
     *  came back after an outage, so refresh history once to pick up any
     *  messages missed while offline. containsMessage(id) dedupe makes the
     *  overlap with realtime delivery safe (no duplicate rows). */
    private final ChatRealtime.Listener realtimeHub =
            new ChatRealtime.Listener() {
        @Override
        public void onChatEvent(long chanId, String eventName, String data) {
            realtime.onChatEvent(chanId, eventName, data);
        }

        @Override
        public void onRealtimeConnected() {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!loading) loadHistory();
            });
        }
    };

    // ── lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_chat_screen);

        channelId = getIntent().getLongExtra(EXTRA_CHANNEL_ID, 0);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        myUserId = getIntent().getLongExtra(EXTRA_MY_USER_ID, 0);
        isGroup = "group".equals(getIntent().getStringExtra(EXTRA_CHANNEL_TYPE))
                || "company".equals(getIntent().getStringExtra(EXTRA_CHANNEL_TYPE))
                || "department".equals(getIntent().getStringExtra(EXTRA_CHANNEL_TYPE));

        messagesList = findViewById(R.id.chat_messages_list);
        messagesScroll = findViewById(R.id.chat_messages_scroll);
        skeleton = findViewById(R.id.chat_messages_skeleton);
        errorBox = findViewById(R.id.chat_messages_error);
        empty = findViewById(R.id.chat_messages_empty);
        composerInput = findViewById(R.id.chat_composer_input);
        sendBtn = findViewById(R.id.chat_composer_send);
        titleView = findViewById(R.id.chat_screen_title);
        subtitleView = findViewById(R.id.chat_screen_subtitle);

        titleView.setText(channelName != null ? channelName : "");

        ImageButton back = findViewById(R.id.chat_screen_back);
        back.setOnClickListener(v -> finish());
        findViewById(R.id.chat_messages_retry).setOnClickListener(v -> loadHistory());
        sendBtn.setOnClickListener(v -> send());
        composerInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                sendBtn.setEnabled(!s.toString().trim().isEmpty());
                sendBtn.setAlpha(s.toString().trim().isEmpty() ? 0.45f : 1f);
            }
        });
        sendBtn.setEnabled(false);
        sendBtn.setAlpha(0.45f);

        messagesScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View child = messagesScroll.getChildAt(0);
            if (child == null) return;
            int bottomDelta = child.getBottom()
                    - (messagesScroll.getScrollY() + messagesScroll.getHeight());
            userScrolledUp = bottomDelta > messagesScroll.getHeight() / 2;
        });

        applyInsets();

        // v1.5.41: automatic network recovery — connectivity returning
        // reloads the conversation history, catching anything missed while
        // offline (the realtime socket reconnects on its own backoff;
        // loadHistory's id-dedupe guarantees no duplicate messages appear
        // even if both the realtime event and this refresh deliver).
        netWatcher = ConnectivityWatcher.register(this, () -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (!loading) loadHistory();
        }));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChatRealtime rt = ChatRealtime.get();
        rt.configure(myUserId, origin());
        rt.listen(String.valueOf(channelId), realtimeHub);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ChatRealtime.get().release(String.valueOf(channelId), realtimeHub);
    }

    private String origin() {
        return BuildConfig.QOLAB_ORIGIN != null ? BuildConfig.QOLAB_ORIGIN
                : "https://workpulse-gbpedt.drytis.dev";
    }

    @SuppressLint("ClickableViewAccessibility")
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                    int status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                    View header = (View) titleView.getParent();
                    header.setPadding(header.getPaddingStart(),
                            status + 10, header.getPaddingEnd(), header.getPaddingBottom());
                    View composer = (View) composerInput.getParent();
                    composer.setPadding(composer.getPaddingStart(), composer.getPaddingTop(),
                            composer.getPaddingEnd(), navBar + 10);
                    return WindowInsetsCompat.CONSUMED;
                });
    }

    // ── history ───────────────────────────────────────────────────────

    private void loadHistory() {
        if (loading || channelId <= 0) return;
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null || !transport.isAlive()) {
            // v1.5.40: verify the session with the server before ever
            // showing Login (SessionGate — transient failures keep the
            // cookie and surface an error instead).
            SessionGate.requireValidSession(this, origin(),
                    () -> runOnUiThread(this::loadHistory),
                    () -> runOnUiThread(() -> {
                        Intent i = new Intent(this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    }),
                    () -> runOnUiThread(() -> {
                        skeleton.setVisibility(View.GONE);
                        errorBox.setVisibility(View.VISIBLE);
                    }));
            return;
        }
        loading = true;
        if (messages.isEmpty()) skeleton.setVisibility(View.VISIBLE);
        transport.getJson("/chat/channels/" + channelId + "/messages?per_page=50&page=1",
                r -> runOnUiThread(() -> {
                    loading = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                        // v1.5.40: verify before logout.
                        SessionGate.requireValidSession(this, origin(),
                                () -> runOnUiThread(this::loadHistory),
                                () -> runOnUiThread(() -> {
                                    Intent i = new Intent(this, LoginActivity.class);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    startActivity(i);
                                    finish();
                                }),
                                () -> runOnUiThread(() -> {
                                    skeleton.setVisibility(View.GONE);
                                    errorBox.setVisibility(View.VISIBLE);
                                }));
                        return;
                    }
                    if (r.status == 401) {
                        Intent i = new Intent(this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                        return;
                    }
                    if (r.status < 200 || r.status >= 300 || r.body == null) {
                        skeleton.setVisibility(View.GONE);
                        errorBox.setVisibility(View.VISIBLE);
                        return;
                    }
                    ChatDataClient.MessagesPage page = ChatDataClient.parseMessages(r.body);
                    if (page == null) {
                        skeleton.setVisibility(View.GONE);
                        errorBox.setVisibility(View.VISIBLE);
                        return;
                    }
                    messages.clear();
                    messages.addAll(page.messages);
                    renderMessages();
                    if (!page.messages.isEmpty()) markRead(
                            page.messages.get(page.messages.size() - 1).id);
                }));
    }

    private void renderMessages() {
        skeleton.setVisibility(View.GONE);
        errorBox.setVisibility(View.GONE);
        messagesList.removeAllViews();
        if (messages.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);
        for (ChatDataClient.ChatMessage m : messages) {
            messagesList.addView(inflateMessage(m));
        }
        messagesScroll.post(() -> messagesScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ── sending ───────────────────────────────────────────────────────

    private void send() {
        if (sending) return; // single-flight: no duplicate sends
        String text = composerInput.getText().toString().trim();
        if (text.isEmpty() || channelId <= 0) return;
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null || !transport.isAlive()) {
            Toast.makeText(this, R.string.chat_send_failed_short, Toast.LENGTH_SHORT).show();
            return;
        }
        sending = true;
        sendBtn.setEnabled(false);
        String body = "{\"body\":" + QolabWebViewTransport.jsonStringSafe(text) + "}";
        transport.postJson("/chat/channels/" + channelId + "/messages", body,
                r -> runOnUiThread(() -> {
                    sending = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (r.status >= 200 && r.status < 300 && r.body != null) {
                        ChatDataClient.ChatMessage sent = parseSent(r.body);
                        if (sent != null && !containsMessage(sent.id)) {
                            appendMessage(sent);
                        }
                        composerInput.setText("");
                        ChatRealtime.get().markMine(sent != null ? sent.id : 0);
                        scrollToBottom();
                    } else if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD
                            || r.status == 401) {
                        Intent i = new Intent(this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    } else {
                        // Network/422/419-after-retry/5xx — keep the text so
                        // nothing is lost; user taps Send again (retry).
                        sendBtn.setEnabled(true);
                        Toast.makeText(this, R.string.chat_send_failed, Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private ChatDataClient.ChatMessage parseSent(String body) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            return ChatDataClient.ChatMessage.fromJson(root.optJSONObject("message"));
        } catch (Exception e) {
            return null;
        }
    }

    // ── message rows ──────────────────────────────────────────────────

    private boolean containsMessage(long id) {
        for (ChatDataClient.ChatMessage m : messages) {
            if (m.id == id) return true;
        }
        return false;
    }

    private void appendMessage(ChatDataClient.ChatMessage m) {
        if (messages.isEmpty()) empty.setVisibility(View.GONE);
        messages.add(m);
        messagesList.addView(inflateMessage(m));
    }

    private View inflateMessage(ChatDataClient.ChatMessage m) {
        View row = getLayoutInflater().inflate(R.layout.view_chat_message, messagesList, false);
        TextView sender = row.findViewById(R.id.chat_msg_sender);
        TextView bubble = row.findViewById(R.id.chat_msg_bubble);
        TextView time = row.findViewById(R.id.chat_msg_time);
        View rowLine = row.findViewById(R.id.chat_msg_row);

        boolean mine = m.userId == myUserId;
        bubble.setText(m.body != null ? m.body : "");

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (mine) {
            bg.setColor(0xFF8B1E2D); // qm_wine
            bubble.setTextColor(0xFFFFFFFF);
            ((LinearLayout.LayoutParams) rowLine.getLayoutParams()).gravity = Gravity.END;
            ((LinearLayout.LayoutParams) rowLine.getLayoutParams()).width =
                    LinearLayout.LayoutParams.WRAP_CONTENT;
            sender.setVisibility(View.GONE);
        } else {
            bg.setColor(0xFFFFFFFF); // qm_card
            bubble.setTextColor(0xFF1A1A1A); // qm_ink
            ((LinearLayout.LayoutParams) rowLine.getLayoutParams()).gravity = Gravity.START;
            if (isGroup && m.senderName != null) {
                sender.setText(m.senderName);
                sender.setVisibility(View.VISIBLE);
            } else {
                sender.setVisibility(View.GONE);
            }
        }
        bubble.setBackground(bg);
        time.setText(timeLabel(m.createdAtIso));
        if (mine) {
            ((LinearLayout.LayoutParams) time.getLayoutParams()).gravity = Gravity.END;
        }
        return row;
    }

    private String timeLabel(String iso) {
        long t = ChatDataClient.parseIsoMillis(iso);
        if (t <= 0) return "";
        return new java.text.SimpleDateFormat("h:mm a", Locale.US)
                .format(new java.util.Date(t));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void scrollToBottom() {
        messagesScroll.post(() -> messagesScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** Fire-and-forget read receipt; failures never block the UI. */
    private void markRead(long lastMessageId) {
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null || !transport.isAlive() || lastMessageId <= 0) return;
        String body = "{\"last_read_message_id\":" + lastMessageId + "}";
        transport.postJson("/chat/channels/" + channelId + "/read", body,
                r -> { /* read receipts are best-effort */ });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // v1.5.41: stop watching connectivity.
        ConnectivityWatcher.unregister(this, netWatcher);
        netWatcher = null;
    }
}
