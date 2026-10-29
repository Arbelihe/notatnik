package pl.prosty.notatnik;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Prosty notatnik: otwórz plik tekstowy, edytuj, zapisz. Nic więcej.
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE_AS = 2;
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Charset CP1250 = Charset.forName("windows-1250");

    private LinearLayout root;
    private EditText editor;
    private TextView titleView;
    private TextView infoView;

    // Stan bieżącego dokumentu
    private Uri fileUri;
    private String fileName;
    private Charset charset = StandardCharsets.UTF_8;
    private boolean bom;
    private String lineSep = "\n";
    private boolean dirty;
    private boolean loading;
    private String draftId;

    /** Akcja do wykonania po udanym zapisie (np. zamknięcie, otwarcie innego pliku). */
    private Runnable afterSave;

    // ------------------------------------------------------------------ cykl życia

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        setupWindow();

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        if (state != null) {
            restoreState(state);
        } else {
            cleanOldDrafts();
            handleIncomingIntent(getIntent());
        }
        updateTitle();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        confirmUnsaved(this::finish);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (draftId == null) draftId = UUID.randomUUID().toString();
        out.putString("draftId", draftId);
        out.putString("uri", fileUri != null ? fileUri.toString() : null);
        out.putString("name", fileName);
        out.putString("charset", charset.name());
        out.putBoolean("bom", bom);
        out.putString("sep", lineSep);
        out.putBoolean("dirty", dirty);
        out.putInt("selStart", editor.getSelectionStart());
        out.putInt("selEnd", editor.getSelectionEnd());
        // Tekst trafia do pliku roboczego, bo Bundle ma limit rozmiaru.
        try (OutputStream os = new FileOutputStream(draftFile(draftId))) {
            os.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private void restoreState(Bundle s) {
        draftId = s.getString("draftId");
        String u = s.getString("uri");
        fileUri = u != null ? Uri.parse(u) : null;
        fileName = s.getString("name");
        try {
            charset = Charset.forName(s.getString("charset", "UTF-8"));
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }
        bom = s.getBoolean("bom");
        lineSep = s.getString("sep", "\n");
        String text = "";
        if (draftId != null) {
            File f = draftFile(draftId);
            try {
                text = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        setEditorText(text);
        dirty = s.getBoolean("dirty");
        int len = editor.length();
        editor.setSelection(clamp(s.getInt("selStart"), len), clamp(s.getInt("selEnd"), len));
    }

    private File draftFile(String id) {
        File dir = new File(getFilesDir(), "drafts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, id + ".txt");
    }

    private void cleanOldDrafts() {
        File[] files = new File(getFilesDir(), "drafts").listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (f.lastModified() < limit) f.delete();
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            Uri uri = intent.getData();
            takePermission(uri, intent.getFlags());
            loadUri(uri);
        }
    }

    // ------------------------------------------------------------------ interfejs

    private void buildUi() {
        int barColor = getColor(R.color.bar);
        int textColor = getColor(R.color.text);
        int dimColor = getColor(R.color.text_dim);
        int accent = getColor(R.color.accent);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(barColor);

        // --- pasek górny
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(6), dp(4), dp(6));
        bar.setMinimumHeight(dp(56));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(this);
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        infoView = new TextView(this);
        infoView.setTextColor(dimColor);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        infoView.setSingleLine(true);

        titles.addView(titleView);
        titles.addView(infoView);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button open = barButton("Otwórz", accent);
        open.setOnClickListener(v -> confirmUnsaved(this::pickFile));
        bar.addView(open);

        Button save = barButton("Zapisz", accent);
        save.setOnClickListener(v -> save());
        bar.addView(save);

        Button more = barButton("⋮", textColor);
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        more.setOnClickListener(this::showMenu);
        bar.addView(more);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // --- edytor
        editor = new EditText(this);
        editor.setSaveEnabled(false); // stan zapisujemy sami (duże pliki nie zmieszczą się w Bundle)
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setHorizontallyScrolling(false);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setBackgroundColor(getColor(R.color.editor));
        editor.setTextColor(textColor);
        editor.setHintTextColor(dimColor);
        editor.setHint("Zacznij pisać…");
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        editor.setLineSpacing(0, 1.12f);
        editor.setPadding(dp(14), dp(12), dp(14), dp(12));
        editor.setVerticalScrollBarEnabled(true);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (!loading && !dirty) {
                    dirty = true;
                    updateTitle();
                }
            }
        });
        root.addView(editor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Button barButton(String label, int color) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Nowy plik");
        m.getMenu().add(0, 2, 1, "Zapisz jako…");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) confirmUnsaved(this::newFile);
            else if (item.getItemId() == 2) saveAs();
            return true;
        });
        m.show();
    }

    private void setupWindow() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= 30) {
            // Rysujemy pod paskami systemu i sami dodajemy odstępy (też nad klawiaturą).
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : mask, mask);
            }
        } else if (Build.VERSION.SDK_INT >= 27 && !night) {
            View d = getWindow().getDecorView();
            //noinspection deprecation
            d.setSystemUiVisibility(d.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void updateTitle() {
        String name = fileName != null ? fileName : "Nowy plik";
        titleView.setText(dirty ? "• " + name : name);

        String cs;
        if (charset.equals(CP1250)) cs = "Windows-1250";
        else if (charset.equals(StandardCharsets.UTF_16LE) || charset.equals(StandardCharsets.UTF_16BE)) cs = "UTF-16";
        else cs = "UTF-8";
        if (bom) cs += " BOM";

        String sep;
        if ("\r\n".equals(lineSep)) sep = "CRLF";
        else if ("\r".equals(lineSep)) sep = "CR";
        else sep = "LF";

        infoView.setText(dirty ? cs + " · " + sep + " · niezapisane" : cs + " · " + sep);
    }

    // ------------------------------------------------------------------ akcje

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            toast("Brak aplikacji do wyboru plików");
        }
    }

    private void newFile() {
        fileUri = null;
        fileName = null;
        charset = StandardCharsets.UTF_8;
        bom = false;
        lineSep = "\n";
        setEditorText("");
        dirty = false;
        updateTitle();
        editor.requestFocus();
    }

    private void save() {
        if (fileUri == null) {
            saveAs();
            return;
        }
        if (writeTo(fileUri)) {
            dirty = false;
            updateTitle();
            toast("Zapisano");
            runAfterSave();
        } else {
            // Np. plik otwarty tylko do odczytu z innej aplikacji – zapisz w nowym miejscu.
            toast("Nie można zapisać w tym miejscu – wybierz, gdzie zapisać");
            saveAs();
        }
    }

    private void saveAs() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        String name = fileName != null ? fileName : "notatka.txt";
        if (!name.contains(".")) name += ".txt";
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_SAVE_AS);
        } catch (Exception e) {
            afterSave = null;
            toast("Brak aplikacji do zapisu plików");
        }
    }

    private void runAfterSave() {
        Runnable r = afterSave;
        afterSave = null;
        if (r != null) r.run();
    }

    /** Jeśli są niezapisane zmiany, pyta co zrobić; potem wykonuje {@code next}. */
    private void confirmUnsaved(Runnable next) {
        if (!dirty) {
            next.run();
            return;
        }
        String name = fileName != null ? "„" + fileName + "”" : "nowym pliku";
        new AlertDialog.Builder(this)
                .setTitle("Niezapisane zmiany")
                .setMessage("Zapisać zmiany w " + name + "?")
                .setPositiveButton("Zapisz", (d, w) -> {
                    afterSave = next;
                    save();
                })
                .setNegativeButton("Nie zapisuj", (d, w) -> next.run())
                .setNeutralButton("Anuluj", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_SAVE_AS) afterSave = null;
            return;
        }
        Uri uri = data.getData();
        takePermission(uri, data.getFlags());

        if (requestCode == REQ_OPEN) {
            loadUri(uri);
        } else if (requestCode == REQ_SAVE_AS) {
            if (writeTo(uri)) {
                fileUri = uri;
                fileName = queryName(uri);
                dirty = false;
                updateTitle();
                toast("Zapisano");
                runAfterSave();
            } else {
                afterSave = null;
            }
        }
    }

    private void takePermission(Uri uri, int flags) {
        int f = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (f == 0) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, f);
        } catch (Exception ignored) {
            // nie każdy dostawca plików na to pozwala – wtedy działa tylko w tej sesji
        }
    }

    // ------------------------------------------------------------------ odczyt

    private void loadUri(Uri uri) {
        final ContentResolver cr = getContentResolver();
        infoView.setText("Wczytywanie…");
        new Thread(() -> {
            String error = null;
            Decoded dec = null;
            String name = null;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) throw new IOException("brak dostępu");
                byte[] bytes = readAll(in);
                dec = decode(bytes);
                if (dec == null) error = "To nie wygląda na plik tekstowy";
                name = queryName(uri);
            } catch (TooLargeException e) {
                error = "Plik jest za duży (limit 20 MB)";
            } catch (SecurityException | IOException e) {
                error = "Nie można otworzyć pliku";
            } catch (OutOfMemoryError e) {
                error = "Plik jest za duży";
            }
            final String err = error;
            final Decoded d = dec;
            final String n = name;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (err != null) {
                    toast(err);
                    updateTitle();
                    return;
                }
                fileUri = uri;
                fileName = n;
                charset = d.charset;
                bom = d.bom;
                lineSep = d.lineSep;
                setEditorText(d.text);
                dirty = false;
                editor.setSelection(0);
                editor.scrollTo(0, 0);
                updateTitle();
            });
        }).start();
    }

    private static final class Decoded {
        String text;
        Charset charset;
        boolean bom;
        String lineSep;
    }

    private static final class TooLargeException extends IOException { }

    /** Rozpoznaje kodowanie (BOM, UTF-8, w razie błędu Windows-1250) i znaki końca linii. */
    private static Decoded decode(byte[] b) {
        Decoded d = new Decoded();
        int off = 0;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            d.charset = StandardCharsets.UTF_8;
            d.bom = true;
            off = 3;
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            d.charset = StandardCharsets.UTF_16LE;
            d.bom = true;
            off = 2;
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            d.charset = StandardCharsets.UTF_16BE;
            d.bom = true;
            off = 2;
        }

        String text;
        if (d.charset != null) {
            text = new String(b, off, b.length - off, d.charset);
        } else {
            // Bajt zerowy w pierwszych 8 KB = najpewniej plik binarny.
            int probe = Math.min(b.length, 8192);
            for (int i = 0; i < probe; i++) if (b[i] == 0) return null;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(b))
                        .toString();
                d.charset = StandardCharsets.UTF_8;
            } catch (CharacterCodingException e) {
                text = new String(b, CP1250); // stare pliki z polskiego Windowsa
                d.charset = CP1250;
            }
        }

        if (text.contains("\r\n")) d.lineSep = "\r\n";
        else if (text.indexOf('\r') >= 0) d.lineSep = "\r";
        else d.lineSep = "\n";
        d.text = text.replace("\r\n", "\n").replace('\r', '\n');
        return d;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new TooLargeException();
            }
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ zapis

    /** Zapisuje w tym samym kodowaniu i z tymi samymi końcami linii, co oryginał. */
    private boolean writeTo(Uri uri) {
        String text = editor.getText().toString();
        if (!"\n".equals(lineSep)) text = text.replace("\n", lineSep);

        byte[] body;
        try {
            CharsetEncoder enc = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = enc.encode(CharBuffer.wrap(text));
            body = new byte[bb.remaining()];
            bb.get(body);
        } catch (CharacterCodingException e) {
            charset = StandardCharsets.UTF_8;
            bom = false;
            body = text.getBytes(StandardCharsets.UTF_8);
            toast("Tekst zawiera znaki spoza Windows-1250 – zapisano jako UTF-8");
        }

        byte[] head = new byte[0];
        if (bom) {
            if (charset.equals(StandardCharsets.UTF_8)) head = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            else if (charset.equals(StandardCharsets.UTF_16LE)) head = new byte[]{(byte) 0xFF, (byte) 0xFE};
            else if (charset.equals(StandardCharsets.UTF_16BE)) head = new byte[]{(byte) 0xFE, (byte) 0xFF};
        }

        ContentResolver cr = getContentResolver();
        OutputStream os;
        try {
            try {
                os = cr.openOutputStream(uri, "wt"); // "wt" = nadpisz od zera
            } catch (FileNotFoundException | IllegalArgumentException | UnsupportedOperationException e) {
                os = cr.openOutputStream(uri, "w");
            }
            if (os == null) return false;
            try (OutputStream o = os) {
                o.write(head);
                o.write(body);
                o.flush();
            }
            return true;
        } catch (SecurityException | IOException | IllegalArgumentException | UnsupportedOperationException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ pomocnicze

    private void setEditorText(String text) {
        loading = true;
        editor.setText(text);
        loading = false;
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        if (last != null && last.contains("/")) last = last.substring(last.lastIndexOf('/') + 1);
        return last != null ? last : "plik.txt";
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
