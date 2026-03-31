package com.hardening.blindspot;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

public class RedditHook implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT_REDDIT";
    private static final Uri PREFS_URI = Uri.parse("content://com.hardening.blindspot.hooks/prefs");
    
    // Long press detection for Compose
    private static Handler handler;
    private static Runnable longPressRunnable;
    private static float downX = 0, downY = 0;

    private static class RenderedPiece implements Comparable<RenderedPiece> {
        String text;
        float yPosition;
        long lastSeen;
        String source;

        public RenderedPiece(String text, float y) {
            this(text, y, "render");
        }

        public RenderedPiece(String text, float y, String source) {
            this.text = text;
            this.yPosition = y;
            this.lastSeen = System.currentTimeMillis();
            this.source = source;
        }

        @Override
        public int compareTo(RenderedPiece o) {
            return Float.compare(this.yPosition, o.yPosition);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, RenderedPiece> screenBuffer = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, RenderedPiece> commentsBuffer = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> recentDataHookLogs = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastSuccessfulExtraction = "";
    private static volatile long lastSuccessfulExtractionAt = 0L;
    private static volatile String latestPostOnlyText = "";
    private static volatile long latestPostOnlyAt = 0L;

    private static boolean isIgnoredText(String text) {
        if (text == null || text.trim().length() < 10) return true; // Ignore empty or very small tags
        
        // Filter out unnecessary profile metrics, dates and buttons to keep only post/comments
        String lower = text.trim().toLowerCase();
        if (looksLikeNoiseToken(text.trim())) return true;
        if (looksLikeInternalOrNonContentText(text.trim())) return true;
        return lower.equals("post karma") || lower.equals("comment karma") || 
            lower.equals("start chat") || lower.equals("official account") || 
            lower.startsWith("official accounts") || lower.equals("trophies") || 
            lower.startsWith("place '") || lower.startsWith("unlocked by") || 
            lower.equals("verified email") || lower.endsWith("-year club") ||
            lower.contains("karma") || lower.equals("awardee karma") ||
            lower.equals("awarder karma") || lower.equals("get premium") ||
            lower.equals("send a message") || lower.equals("block account");
    }

    private static boolean looksLikeInternalOrNonContentText(String s) {
        String t = s.trim();
        String lower = t.toLowerCase(Locale.ROOT);

        if (lower.startsWith("http://") || lower.startsWith("https://")) return true;
        if (lower.startsWith("android_")) return true;
        if (lower.startsWith("pdp_")) return true;
        if (lower.startsWith("category_")) return true;
        if (lower.startsWith("allow_")) return true;
        if (lower.startsWith("t2_")) return true;
        if (lower.startsWith("self.")) return true;
        if (lower.contains("compositionlocal")) return true;
        if (lower.contains("profileicon_")) return true;
        if (lower.contains("redditmedia.com")) return true;
        if (lower.contains("i.redd.it/")) return true;
        if (lower.contains("styles.redditmedia.com")) return true;

        // JSON-ish payloads from API models.
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) return true;
        if (lower.contains("\"document\"") || lower.contains("\"e\":\"text\"")) return true;
        if (lower.equals("best comments")) return true;

        // Version/build tokens.
        if (t.matches("^\\d{4}\\.\\d{1,2}\\.\\d+(\\.\\d+)?$")) return true;

        return false;
    }

    private static boolean looksLikeNoiseToken(String s) {
        String t = s.trim();
        // UUID
        if (t.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) {
            return true;
        }
        // JWT
        if (t.matches("^[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}$")) {
            return true;
        }
        // Base64/Base64URL-like long opaque tokens (avoid filtering normal sentences)
        if (t.length() >= 40 && t.matches("^[A-Za-z0-9+/=_-]+$")) {
            int letters = 0;
            for (int i = 0; i < t.length(); i++) {
                if (Character.isLetter(t.charAt(i))) letters++;
            }
            if ((double) letters / (double) t.length() > 0.65) {
                return true;
            }
        }
        // Long hex-ish IDs
        if (t.length() >= 24 && t.matches("(?i)^[0-9a-f]+$")) {
            return true;
        }
        return false;
    }

    private static void captureText(CharSequence seq, android.graphics.Canvas canvas) {
        if (seq == null) return;
        String text = seq.toString().trim();
        if (text.length() < 3 || isIgnoredText(text)) return;

        float[] matrixValues = new float[9];
        canvas.getMatrix().getValues(matrixValues);
        float absoluteY = matrixValues[android.graphics.Matrix.MTRANS_Y];

        // Updates timestamp continuously, locking it into the active frame buffer
        screenBuffer.put(text, new RenderedPiece(text, absoluteY, "render"));
    }

    private static void captureText(CharSequence seq, float y) {
        if (seq == null) return;
        String text = seq.toString().trim();
        if (text.length() < 3 || isIgnoredText(text)) return;
        screenBuffer.put(text, new RenderedPiece(text, y, "render"));
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.reddit.frontpage")) {
            return;
        }

        Log.e(TAG, "!!! REDDIT TEXT SELECTION HOOK INITIALIZED for: " + lpparam.packageName + " !!!");
        hookRedditDataPipeline(lpparam.classLoader);

        // Log Activities being resumed to find the Post Detail page
        XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                android.app.Activity activity = (android.app.Activity) param.thisObject;
                Log.e(TAG, "+++ Activity Resumed: " + activity.getClass().getName());
            }
        });

        // 1. Hook the lowest level Android Text Drawing. 
        // Even Jetpack Compose uses Layout.draw() to render complex paragraphs!
        XposedHelpers.findAndHookMethod(android.text.Layout.class, "draw", android.graphics.Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                android.text.Layout layout = (android.text.Layout) param.thisObject;
                android.graphics.Canvas canvas = (android.graphics.Canvas) param.args[0];
                captureText(layout.getText(), canvas);
            }
        });

        // Compose can bypass Layout.draw in newer builds. Capture canvas text calls too.
        XposedHelpers.findAndHookMethod(
                android.graphics.Canvas.class,
                "drawText",
                CharSequence.class, int.class, int.class, float.class, float.class, android.graphics.Paint.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        CharSequence source = (CharSequence) param.args[0];
                        int start = (int) param.args[1];
                        int end = (int) param.args[2];
                        float y = (float) param.args[4];
                        if (source != null && start >= 0 && end > start && end <= source.length()) {
                            captureText(source.subSequence(start, end), y);
                        }
                    }
                }
        );

        // Hook TextView properties to allow text selection everywhere
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                
                boolean isEnabled = isHookEnabled(v.getContext());
                if (!isEnabled) {
                    return;
                }
                
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    try {
                        CharSequence text = tv.getText();
                        String textSnippet = text != null ? text.toString().replace("\n", " ").trim() : "";
                        
                        // Never hook or process completely irrelevant UI texts (like profile karma/badges or very small texts)
                        if (isIgnoredText(textSnippet)) {
                            return;
                        }
                        
                        if (textSnippet.length() > 20) {
                            textSnippet = textSnippet.substring(0, 20) + "...";
                        }

                        if (!tv.isTextSelectable()) {
                            tv.setTextIsSelectable(true);
                        }
                        
                        // Force properties that allow selection
                        tv.setFocusable(true);
                        tv.setFocusableInTouchMode(true);
                        tv.setLongClickable(true);
                        tv.setClickable(true);
                        tv.setCustomSelectionActionModeCallback(null); // Remove any custom action mode that might block it
                        
                        // Prevent parent ViewGroup (like RecyclerView or CardView) from stealing the long-press event
                        tv.setOnTouchListener(new View.OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, android.view.MotionEvent event) {
                                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                                    if (v.getParent() != null) {
                                        v.getParent().requestDisallowInterceptTouchEvent(true);
                                    }
                                }
                                return false; // let the TextView handle the touch normally (for selection)
                            }
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Failed to set text selectable for TextView: " + e.getMessage());
                    }
                }
            }
        });

        // Intercept attempts to disable text selection programmatically
        XposedHelpers.findAndHookMethod(TextView.class, "setTextIsSelectable", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                TextView tv = (TextView) param.thisObject;
                
                if (!isHookEnabled(tv.getContext())) {
                    return;
                }
                
                boolean isSelectable = (boolean) param.args[0];
                if (!isSelectable) {
                    param.args[0] = true; // Force it to remain selectable
                }
            }
        });

        // Hook View performLongClick to see what view is actually processing long clicks
        XposedHelpers.findAndHookMethod(View.class, "performLongClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    if (!tv.isTextSelectable()) {
                        tv.setTextIsSelectable(true);
                    }
                }
            }
        });

        // Hook Activity dispatchTouchEvent to trace raw touches and implement a custom long-press detector for Compose
        XposedHelpers.findAndHookMethod(android.app.Activity.class, "dispatchTouchEvent", android.view.MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                android.app.Activity activity = (android.app.Activity) param.thisObject;
                android.view.MotionEvent event = (android.view.MotionEvent) param.args[0];
                
                if (!isHookEnabled(activity)) return;
                
                if (handler == null) {
                    handler = new Handler(Looper.getMainLooper());
                }
                
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        
                        if (longPressRunnable != null) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                        
                        View decorView = activity.getWindow().getDecorView();
                        View target = findViewAtLocation(decorView, (int)downX, (int)downY);
                        
                        if (target != null) {
                            String targetClass = target.getClass().getName();
                            Log.e(TAG, "Touch ACTION_DOWN Target View: " + targetClass + " inside Activity: " + activity.getClass().getSimpleName());
                            logDetectedRedditSurface(activity, targetClass);
                            
                            // If it's a TextView, our other hooks handle it. If it's Compose, we use the fallback popup.
                            if (targetClass.contains("compose") || targetClass.contains("ComposeView")) {
                                longPressRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        Log.e(TAG, "COMPOSE LONG PRESS DETECTED! Showing drawn text popup...");
                                        showComposeTextExtractorPopup(activity);
                                    }
                                };
                                handler.postDelayed(longPressRunnable, 750); // 750ms for long press
                            } else if (target instanceof TextView) {
                                TextView tv = (TextView) target;
                                if (!tv.isTextSelectable()) {
                                    tv.setTextIsSelectable(true);
                                    tv.setCustomSelectionActionModeCallback(null);
                                    tv.setFocusableInTouchMode(true);
                                }
                                if (target.getParent() != null) {
                                    target.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                            }
                        }
                        break;
                        
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(event.getRawX() - downX);
                        float dy = Math.abs(event.getRawY() - downY);
                        if (dx > 20 || dy > 20) { // Touch slop
                            if (longPressRunnable != null) {
                                handler.removeCallbacks(longPressRunnable);
                            }
                        }
                        break;
                        
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (longPressRunnable != null) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                        break;
                }
            }
        });
    }
    private void showComposeTextExtractorPopup(android.app.Activity activity) {
        try {
            long currentFrameTime = System.currentTimeMillis();

            java.util.ArrayList<RenderedPiece> activePieces = new java.util.ArrayList<>();
            for (RenderedPiece piece : commentsBuffer.values()) {
                if (currentFrameTime - piece.lastSeen < 30000) {
                    activePieces.add(piece);
                } else {
                    commentsBuffer.remove(piece.text);
                }
            }

            if (activePieces.isEmpty()) {
                for (RenderedPiece piece : screenBuffer.values()) {
                    // Keep data-hook captures longer; Reddit pipelines can run seconds before touch.
                    if (currentFrameTime - piece.lastSeen < 20000) {
                        activePieces.add(piece);
                    } else {
                        screenBuffer.remove(piece.text);
                    }
                }
            }

            java.util.Collections.sort(activePieces);

            StringBuilder sb = new StringBuilder();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (RenderedPiece piece : activePieces) {
                String t = piece.text != null ? piece.text.trim() : "";
                if (t.length() < 3 || isIgnoredText(t) || seen.contains(t)) {
                    continue;
                }
                seen.add(t);
                sb.append("- ").append(t).append("\n\n");
            }

            if (sb.length() == 0) {
                Set<String> markers = extractAccessibilityMarkers(activity);
                for (String marker : markers) {
                    String t = marker != null ? marker.trim() : "";
                    if (t.length() >= 12 && !isIgnoredText(t) && !seen.contains(t) && !t.startsWith("id:")) {
                        seen.add(t);
                        sb.append("- ").append(t).append("\n\n");
                    }
                }
            }

            String extracted;
            long postAge = System.currentTimeMillis() - latestPostOnlyAt;
            if (!latestPostOnlyText.isEmpty() && postAge < 120000) {
                extracted = "Post\n\n" + latestPostOnlyText;
                lastSuccessfulExtraction = extracted;
                lastSuccessfulExtractionAt = System.currentTimeMillis();
            } else if (sb.length() == 0) {
                Log.e(TAG, "Rendered and accessibility buffers were empty on long press.");
                long ageMs = System.currentTimeMillis() - lastSuccessfulExtractionAt;
                if (!lastSuccessfulExtraction.isEmpty() && ageMs < 120000) {
                    extracted = lastSuccessfulExtraction + "\n\n(Showing last captured content)";
                } else {
                    extracted = "No post/comment text captured yet.\n\nTry long-pressing directly on the post body or a comment after content fully loads.";
                }
            } else {
                extracted = "Post & Comments\n\n" + sb.toString().trim();
                lastSuccessfulExtraction = extracted;
                lastSuccessfulExtractionAt = System.currentTimeMillis();
            }
            final String finalExtracted = extracted;

            Log.e(TAG, "Successfully extracted and showing Compose text:\n" + finalExtracted);

            new Handler(Looper.getMainLooper()).post(() -> {
                TextView tv = new TextView(activity);
                tv.setText(finalExtracted);
                tv.setTextIsSelectable(true);
                tv.setPadding(60, 60, 60, 60);
                tv.setTextSize(16f);
                tv.setTextColor(0xFFFFFFFF);

                android.widget.ScrollView scrollView = new android.widget.ScrollView(activity);
                scrollView.addView(tv);

                new AlertDialog.Builder(activity)
                    .setTitle("Select Partial Text")
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .show();
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to render Compose text popup: " + e.getMessage());
        }
    }
    private View findViewAtLocation(View view, int x, int y) {
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                int[] location = new int[2];
                child.getLocationOnScreen(location);
                android.graphics.Rect rect = new android.graphics.Rect(location[0], location[1], location[0] + child.getWidth(), location[1] + child.getHeight());
                if (rect.contains(x, y) && child.getVisibility() == View.VISIBLE) {
                    View found = findViewAtLocation(child, x, y);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return view;
    }

    private void hookRedditDataPipeline(ClassLoader cl) {
        // Morphe fingerprints indicate these are stable classes in recent Reddit builds.
        hookClassIfPresent(cl, "com.reddit.comments.presentation.CommentsViewModel");
        hookClassIfPresent(cl, "com.reddit.domain.model.listing.Listing");
        hookClassIfPresent(cl, "com.reddit.domain.model.listing.SubmittedListing");
    }

    private void hookClassIfPresent(ClassLoader cl, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            Log.e(TAG, "DataHook attached: " + className);

            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        harvestObjectForText(param.thisObject, "ctor:" + className);
                    }
                });
            }

            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null) {
                            for (Object arg : param.args) {
                                harvestObjectForText(arg, "arg:" + className + "#" + m.getName());
                            }
                        }
                        harvestObjectForText(param.getResult(), "ret:" + className + "#" + m.getName());
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "DataHook skip " + className + ": " + t.getClass().getSimpleName());
        }
    }

    private void harvestObjectForText(Object root, String sourceTag) {
        if (root == null) return;
        Set<String> out = new HashSet<>();
        IdentityHashMap<Object, Boolean> seenObjects = new IdentityHashMap<>();
        collectStringsDeep(root, out, seenObjects, 0);
        boolean isCommentsSource = sourceTag.contains("comments.presentation.CommentsViewModel");
        String bestPost = null;
        int bestScore = Integer.MIN_VALUE;
        int added = 0;
        for (String s : out) {
            if (s.length() >= 12 && !isIgnoredText(s)) {
                RenderedPiece piece = new RenderedPiece(s, added, sourceTag);
                if (isCommentsSource) {
                    commentsBuffer.put(s, piece);
                    int sc = scorePostCandidate(s);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestPost = s;
                    }
                } else {
                    screenBuffer.put(s, piece);
                }
                added++;
                if (added >= 60) break;
            }
        }
        if (isCommentsSource && bestPost != null && bestScore > 0) {
            latestPostOnlyText = normalizePostText(bestPost);
            latestPostOnlyAt = System.currentTimeMillis();
        }
        if (added > 0 && shouldLogDataHook(sourceTag, added)) {
            Log.e(TAG, "DataHook captured " + added + " text nodes from " + sourceTag);
        }
    }

    private boolean shouldLogDataHook(String sourceTag, int added) {
        if (added < 2) return false;

        // Avoid noisy synthetic helpers.
        if (sourceTag.contains("#copy$default") || sourceTag.contains("#copy")) return false;

        long now = System.currentTimeMillis();
        Long last = recentDataHookLogs.get(sourceTag);
        if (last != null && (now - last) < 5000) return false;
        recentDataHookLogs.put(sourceTag, now);
        return true;
    }

    private boolean looksLikeUsernameOnly(String s) {
        String t = s.trim();
        if (t.startsWith("u/")) return true;
        return t.matches("^[A-Za-z][A-Za-z0-9_]{2,24}$");
    }

    private int scorePostCandidate(String s) {
        String t = s.trim();
        if (isIgnoredText(t)) return -1000;
        if (looksLikeUsernameOnly(t)) return -500;
        if (t.length() < 40) return -200;
        int score = Math.min(t.length(), 1200);
        if (t.contains(". ") || t.contains("\n")) score += 120;
        if (t.split("\\s+").length > 20) score += 180;
        return score;
    }

    private String normalizePostText(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace('\r', '\n').trim();
        // If there are no blank-line paragraph separators, treat single newlines as soft wraps.
        if (!t.contains("\n\n") && t.contains("\n")) {
            t = t.replace('\n', ' ').replaceAll("\\s{2,}", " ").trim();
        }
        return t;
    }

    private void collectStringsDeep(Object node, Set<String> out, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (node == null || depth > 4 || out.size() >= 200) return;

        if (node instanceof CharSequence) {
            String s = node.toString().trim();
            if (!s.isEmpty() && s.length() <= 2000) out.add(s);
            return;
        }
        if (node.getClass().isPrimitive() || node instanceof Number || node instanceof Boolean || node instanceof Enum<?>) {
            return;
        }
        if (seen.containsKey(node)) return;
        seen.put(node, true);

        Class<?> cls = node.getClass();
        if (cls.isArray()) {
            int len = Array.getLength(node);
            for (int i = 0; i < len && i < 120; i++) {
                collectStringsDeep(Array.get(node, i), out, seen, depth + 1);
            }
            return;
        }
        if (node instanceof Iterable<?>) {
            int i = 0;
            for (Object item : (Iterable<?>) node) {
                collectStringsDeep(item, out, seen, depth + 1);
                i++;
                if (i >= 120) break;
            }
            return;
        }
        if (node instanceof Map<?, ?>) {
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                collectStringsDeep(e.getKey(), out, seen, depth + 1);
                collectStringsDeep(e.getValue(), out, seen, depth + 1);
                i++;
                if (i >= 120) break;
            }
            return;
        }

        Field[] fields = cls.getDeclaredFields();
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                collectStringsDeep(f.get(node), out, seen, depth + 1);
            } catch (Throwable ignored) {
            }
        }
    }

    private void logDetectedRedditSurface(android.app.Activity activity, String targetClass) {
        try {
            android.content.Intent intent = activity.getIntent();
            String data = intent != null && intent.getData() != null ? intent.getData().toString() : "";
            String extraInitialUrl = intent != null ? intent.getStringExtra("com.reddit.extra.initial_url") : null;

            Set<String> markers = extractAccessibilityMarkers(activity);
            String surface = classifySurface(data, markers);

            Log.e(TAG, "SurfaceGuess=" + surface
                    + " | activity=" + activity.getClass().getName()
                    + " | target=" + targetClass
                    + " | data=" + data
                    + " | initial_url=" + (extraInitialUrl != null ? extraInitialUrl : "")
                    + " | markers=" + markers);
        } catch (Throwable t) {
            Log.e(TAG, "Surface detection failed: " + t.getMessage());
        }
    }

    private String classifySurface(String data, Set<String> markers) {
        String lowerData = data == null ? "" : data.toLowerCase(Locale.ROOT);

        if (lowerData.contains("/comments/")) {
            return "POST_DETAIL";
        }
        if (lowerData.contains("/user/") || lowerData.contains("/u/")) {
            return "PROFILE";
        }

        if (containsAny(markers, "add a comment", "sort comments", "view all comments", "comments")) {
            return "POST_DETAIL";
        }
        if (containsAny(markers, "post karma", "comment karma", "followers", "following", "achievements", "trophies")) {
            return "PROFILE";
        }
        if (containsAny(markers, "home", "popular", "latest", "communities", "inbox", "chat", "profile")) {
            return "FEED_OR_NAV";
        }

        return "UNKNOWN";
    }

    private boolean containsAny(Set<String> haystack, String... needles) {
        for (String item : haystack) {
            String lower = item.toLowerCase(Locale.ROOT);
            for (String n : needles) {
                if (lower.contains(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> extractAccessibilityMarkers(android.app.Activity activity) {
        Set<String> out = new HashSet<>();
        try {
            View root = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (root == null) return out;

            AccessibilityNodeInfo node = root.createAccessibilityNodeInfo();
            if (node != null) {
                walkA11yNode(node, out, 0, 180);
                node.recycle();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private int walkA11yNode(AccessibilityNodeInfo node, Set<String> out, int seen, int maxNodes) {
        if (node == null || seen >= maxNodes) return seen;
        seen++;

        CharSequence text = node.getText();
        if (text != null) {
            String s = text.toString().trim();
            if (!s.isEmpty() && s.length() <= 80) out.add(s);
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString().trim();
            if (!s.isEmpty() && s.length() <= 80) out.add(s);
        }

        String viewId = node.getViewIdResourceName();
        if (viewId != null && !viewId.isEmpty()) {
            out.add("id:" + viewId);
        }

        for (int i = 0; i < node.getChildCount() && seen < maxNodes; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                seen = walkA11yNode(child, out, seen, maxNodes);
                child.recycle();
            }
        }
        return seen;
    }

    private boolean isHookEnabled(Context context) {
        try {
            Cursor cursor = context.getContentResolver().query(
                PREFS_URI,
                null,
                null,
                new String[]{"hook_reddit_enabled"},
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(cursor.getColumnIndex("value"));
                cursor.close();
                return "true".equals(value);
            }
            
            if (cursor != null) cursor.close();
            return true; // Default to enabled if error
        } catch (Exception e) {
            Log.e(TAG, "Error reading from ContentProvider: " + e.getMessage());
            return true; // Default to enabled if error
        }
    }
}

