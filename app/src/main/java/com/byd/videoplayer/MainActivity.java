package com.byd.videoplayer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    private PlayerView playerView;
    private ExoPlayer player;
    private LinearLayout sidebarContainer;
    private TextView tvNowPlaying, tvCurrentPath, tvAudioOverlay;
    private Button btnToggleSidebar, btnTabPlaylist, btnTabBrowser;
    private Button btnActionLeft, btnActionRight, btnAspect, btnAutoBoot;
    private ListView listViewItems;

    private SharedPreferences prefs;
    private final List<String> playlistPaths = new ArrayList<>();
    private final List<File> browserFiles = new ArrayList<>();
    private final List<String> displayList = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;

    private boolean isShowingPlaylist = true;
    private File currentBrowserDir = null;
    private int currentPlayIndex = 0;
    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int usbWaitAttempts = 0;

    private static final String[] SUPPORTED_EXT = {
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".ts", ".m4v",
            ".mp3", ".flac", ".wav", ".m4a", ".ogg", ".aac"
    };

    private final Runnable positionSaverRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                savePlaybackState();
            }
            mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("BYDPlayerPrefs", Context.MODE_PRIVATE);

        initViews();
        checkAndRequestPermissions();
        initPlayer();
        loadSavedPlaylist();

        attemptAutoResumeWithUsbWait();
    }

    private void initViews() {
        playerView = findViewById(R.id.playerView);
        sidebarContainer = findViewById(R.id.sidebarContainer);
        tvNowPlaying = findViewById(R.id.tvNowPlaying);
        tvCurrentPath = findViewById(R.id.tvCurrentPath);
        tvAudioOverlay = findViewById(R.id.tvAudioOverlay);
        btnToggleSidebar = findViewById(R.id.btnToggleSidebar);
        btnTabPlaylist = findViewById(R.id.btnTabPlaylist);
        btnTabBrowser = findViewById(R.id.btnTabBrowser);
        btnActionLeft = findViewById(R.id.btnActionLeft);
        btnActionRight = findViewById(R.id.btnActionRight);
        btnAspect = findViewById(R.id.btnAspect);
        btnAutoBoot = findViewById(R.id.btnAutoBoot);
        listViewItems = findViewById(R.id.listViewItems);

        Button btnPrev = findViewById(R.id.btnPrev);
        Button btnNext = findViewById(R.id.btnNext);

        listAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayList) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(15f);
                if (isShowingPlaylist && position == currentPlayIndex) {
                    tv.setTextColor(Color.parseColor("#00E5FF"));
                }
                return view;
            }
        };
        listViewItems.setAdapter(listAdapter);

        btnToggleSidebar.setOnClickListener(v -> {
            if (sidebarContainer.getVisibility() == View.VISIBLE) {
                sidebarContainer.setVisibility(View.GONE);
            } else {
                sidebarContainer.setVisibility(View.VISIBLE);
            }
        });

        btnTabPlaylist.setOnClickListener(v -> switchToPlaylistTab());
        btnTabBrowser.setOnClickListener(v -> switchToBrowserTab());

        btnActionLeft.setOnClickListener(v -> {
            if (isShowingPlaylist) {
                switchToBrowserTab();
            } else {
                addAllMediaInCurrentFolder();
            }
        });

        btnActionRight.setOnClickListener(v -> {
            if (isShowingPlaylist) {
                playlistPaths.clear();
                currentPlayIndex = 0;
                savePlaylistToPrefs();
                refreshPlaylistView();
                Toast.makeText(this, "Çalma listesi temizlendi", Toast.LENGTH_SHORT).show();
            } else {
                showStorageRoots();
            }
        });

        btnPrev.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> playNext());

        btnAspect.setOnClickListener(v -> cycleAspectRatio());

        updateAutoBootButton();
        btnAutoBoot.setOnClickListener(v -> {
            boolean current = prefs.getBoolean("auto_start_boot", true);
            prefs.edit().putBoolean("auto_start_boot", !current).apply();
            updateAutoBootButton();
            if (!current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Araç açılışında otomatik başlaması için Diğer Uygulamaların Üzerinde Göster izni verin", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            }
        });

        listViewItems.setOnItemClickListener((parent, view, position, id) -> {
            if (isShowingPlaylist) {
                if (position >= 0 && position < playlistPaths.size()) {
                    playTrackAt(position, 0);
                }
            } else {
                handleBrowserClick(position);
            }
        });

        listViewItems.setOnItemLongClickListener((parent, view, position, id) -> {
            if (isShowingPlaylist && position >= 0 && position < playlistPaths.size()) {
                String removed = new File(playlistPaths.remove(position)).getName();
                if (currentPlayIndex >= playlistPaths.size() && currentPlayIndex > 0) {
                    currentPlayIndex--;
                }
                savePlaylistToPrefs();
                refreshPlaylistView();
                Toast.makeText(this, "Listeden çıkarıldı: " + removed, Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
    }

    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    playNext();
                }
            }
        });
    }

    private void attemptAutoResumeWithUsbWait() {
        if (playlistPaths.isEmpty()) {
            switchToBrowserTab();
            return;
        }

        currentPlayIndex = prefs.getInt("last_play_index", 0);
        if (currentPlayIndex < 0 || currentPlayIndex >= playlistPaths.size()) {
            currentPlayIndex = 0;
        }

        final String targetPath = playlistPaths.get(currentPlayIndex);
        final long savedPos = prefs.getLong("last_play_pos_ms", 0);

        File targetFile = new File(targetPath);
        if (targetFile.exists()) {
            sidebarContainer.setVisibility(View.GONE);
            playTrackAt(currentPlayIndex, savedPos);
        } else {
            tvNowPlaying.setText("⏳ USB / SD Kart bekleniyor... (" + (usbWaitAttempts + 1) + "/20)");
            usbWaitAttempts++;
            if (usbWaitAttempts < 20) {
                mainHandler.postDelayed(this::attemptAutoResumeWithUsbWait, 1500);
            } else {
                tvNowPlaying.setText("⚠️ Son oynatılan USB dosyası bulunamadı");
                switchToPlaylistTab();
            }
        }
    }

    private void playTrackAt(int index, long startPositionMs) {
        if (index < 0 || index >= playlistPaths.size()) return;

        String path = playlistPaths.get(index);
        File file = new File(path);
        if (!file.exists()) {
            Toast.makeText(this, "Dosya bulunamadı (USB takılı mı?): " + file.getName(), Toast.LENGTH_SHORT).show();
            return;
        }

        currentPlayIndex = index;
        prefs.edit().putInt("last_play_index", currentPlayIndex).apply();

        boolean isAudio = isAudioFile(file.getName());
        tvAudioOverlay.setVisibility(isAudio ? View.VISIBLE : View.GONE);
        if (isAudio) {
            tvAudioOverlay.setText("🎵 " + file.getName());
        }

        tvNowPlaying.setText("▶ " + file.getName());
        listAdapter.notifyDataSetChanged();

        MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(file));
        player.setMediaItem(mediaItem);
        player.prepare();
        if (startPositionMs > 0) {
            player.seekTo(startPositionMs);
        }
        player.setPlayWhenReady(true);
    }

    private void playNext() {
        if (playlistPaths.isEmpty()) return;
        int nextIndex = (currentPlayIndex + 1) % playlistPaths.size();
        playTrackAt(nextIndex, 0);
    }

    private void playPrevious() {
        if (playlistPaths.isEmpty()) return;
        int prevIndex = (currentPlayIndex - 1 + playlistPaths.size()) % playlistPaths.size();
        playTrackAt(prevIndex, 0);
    }

    private void cycleAspectRatio() {
        if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
            btnAspect.setText("📐 Doldur");
        } else if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            btnAspect.setText("📐 Yakınlaştır");
        } else {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
            btnAspect.setText("📐 Sığdır");
        }
        playerView.setResizeMode(currentResizeMode);
    }

    private void updateAutoBootButton() {
        boolean auto = prefs.getBoolean("auto_start_boot", true);
        if (auto) {
            btnAutoBoot.setText("🚗 Oto-Başlat: AÇIK");
            btnAutoBoot.setBackgroundColor(Color.parseColor("#065F46"));
        } else {
            btnAutoBoot.setText("🚗 Oto-Başlat: KAPALI");
            btnAutoBoot.setBackgroundColor(Color.parseColor("#334155"));
        }
    }

    private void switchToPlaylistTab() {
        isShowingPlaylist = true;
        btnTabPlaylist.setBackgroundColor(Color.parseColor("#0284C7"));
        btnTabBrowser.setBackgroundColor(Color.parseColor("#334155"));
        btnActionLeft.setText("📁 USB'den Ekle");
        btnActionRight.setText("🗑️ Listeyi Temizle");
        refreshPlaylistView();
    }

    private void switchToBrowserTab() {
        isShowingPlaylist = false;
        btnTabBrowser.setBackgroundColor(Color.parseColor("#0284C7"));
        btnTabPlaylist.setBackgroundColor(Color.parseColor("#334155"));
        btnActionLeft.setText("➕ Klasördekileri Ekle");
        btnActionRight.setText("🔄 Diskleri Listele");
        if (currentBrowserDir != null && currentBrowserDir.exists()) {
            openDirectory(currentBrowserDir);
        } else {
            showStorageRoots();
        }
    }

    private void refreshPlaylistView() {
        displayList.clear();
        tvCurrentPath.setText("Çalma Listem (" + playlistPaths.size() + " medya) - Silmek için basılı tutun");
        for (int i = 0; i < playlistPaths.size(); i++) {
            File f = new File(playlistPaths.get(i));
            String prefix = (i == currentPlayIndex) ? "▶ " : (i + 1) + ". ";
            displayList.add(prefix + f.getName());
        }
        listAdapter.notifyDataSetChanged();
    }

    private void showStorageRoots() {
        currentBrowserDir = null;
        browserFiles.clear();
        displayList.clear();
        tvCurrentPath.setText("USB Bellek / SD Kart / Depolama Seçin:");

        List<File> roots = new ArrayList<>();
        File storageRoot = new File("/storage");
        File[] volumes = storageRoot.listFiles();
        if (volumes != null) {
            for (File vol : volumes) {
                if (vol.isDirectory() && vol.canRead() && !vol.getName().equalsIgnoreCase("self")) {
                    roots.add(vol);
                }
            }
        }

        File extRoot = Environment.getExternalStorageDirectory();
        if (extRoot != null && extRoot.exists() && !roots.contains(extRoot)) {
            roots.add(extRoot);
        }

        for (File r : roots) {
            browserFiles.add(r);
            String label = r.getAbsolutePath().contains("emulated") ? "📱 Dahili Depolama (" + r.getName() + ")" : "🔌 USB / SD Kart (" + r.getName() + ")";
            displayList.add(label);
        }

        listAdapter.notifyDataSetChanged();
    }

    private void openDirectory(File dir) {
        currentBrowserDir = dir;
        browserFiles.clear();
        displayList.clear();
        tvCurrentPath.setText(dir.getAbsolutePath());

        if (dir.getParentFile() != null && dir.getParentFile().canRead() && !dir.getAbsolutePath().equals("/storage")) {
            browserFiles.add(dir.getParentFile());
            displayList.add("⬆️ [ .. Üst Klasöre Dön ]");
        }

        File[] children = dir.listFiles();
        if (children != null) {
            Arrays.sort(children, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            for (File child : children) {
                if (child.isHidden()) continue;
                if (child.isDirectory()) {
                    browserFiles.add(child);
                    displayList.add("📁 " + child.getName());
                } else if (isSupportedMedia(child.getName())) {
                    browserFiles.add(child);
                    String icon = isAudioFile(child.getName()) ? "🎵 " : "🎬 ";
                    displayList.add(icon + child.getName());
                }
            }
        }
        listAdapter.notifyDataSetChanged();
    }

    private void handleBrowserClick(int position) {
        if (position < 0 || position >= browserFiles.size()) return;
        File selected = browserFiles.get(position);
        if (selected.isDirectory()) {
            openDirectory(selected);
        } else {
            String path = selected.getAbsolutePath();
            if (!playlistPaths.contains(path)) {
                playlistPaths.add(path);
                savePlaylistToPrefs();
            }
            int idx = playlistPaths.indexOf(path);
            playTrackAt(idx, 0);
            Toast.makeText(this, "Listeye eklendi ve oynatılıyor", Toast.LENGTH_SHORT).show();
        }
    }

    private void addAllMediaInCurrentFolder() {
        if (currentBrowserDir == null || !currentBrowserDir.exists()) {
            Toast.makeText(this, "Önce bir USB veya klasörün içine girin", Toast.LENGTH_SHORT).show();
            return;
        }
        File[] files = currentBrowserDir.listFiles();
        if (files == null) return;

        List<File> mediaList = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && isSupportedMedia(f.getName())) {
                mediaList.add(f);
            }
        }
        Collections.sort(mediaList, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        int addedCount = 0;
        for (File m : mediaList) {
            if (!playlistPaths.contains(m.getAbsolutePath())) {
                playlistPaths.add(m.getAbsolutePath());
                addedCount++;
            }
        }

        savePlaylistToPrefs();
        Toast.makeText(this, addedCount + " medya dosyası çalma listesine eklendi!", Toast.LENGTH_SHORT).show();
        switchToPlaylistTab();
        if (!playlistPaths.isEmpty() && !player.isPlaying()) {
            playTrackAt(0, 0);
        }
    }

    private boolean isSupportedMedia(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private boolean isAudioFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav")
                || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".aac");
    }

    private void savePlaylistToPrefs() {
        JSONArray arr = new JSONArray();
        for (String p : playlistPaths) {
            arr.put(p);
        }
        prefs.edit().putString("saved_playlist_json", arr.toString()).apply();
    }

    private void loadSavedPlaylist() {
        playlistPaths.clear();
        String json = prefs.getString("saved_playlist_json", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                playlistPaths.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
    }

    private void savePlaybackState() {
        if (player == null || playlistPaths.isEmpty()) return;
        long pos = player.getCurrentPosition();
        prefs.edit()
                .putInt("last_play_index", currentPlayIndex)
                .putLong("last_play_pos_ms", Math.max(0, pos))
                .apply();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.postDelayed(positionSaverRunnable, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePlaybackState();
        mainHandler.removeCallbacks(positionSaverRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaybackState();
    }

    @Override
    protected void onDestroy() {
        savePlaybackState();
        mainHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}