package org.askesis.zettel;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Ponte nativa do Askesis-Zettel.
 *
 * Duas responsabilidades:
 *
 * 1. ARQUIVOS — expõe o Storage Access Framework com uma semântica que
 *    espelha a File System Access API do navegador, para que o shim em JS
 *    consiga emular showDirectoryPicker/showOpenFilePicker/showSaveFilePicker
 *    sem que o código do app precise mudar.
 *
 *    Um "handle" é identificado por um par (tree, doc):
 *      - tree: a URI da árvore concedida pelo usuário (ACTION_OPEN_DOCUMENT_TREE)
 *      - doc : o documentId do item dentro dessa árvore
 *    Arquivos avulsos (ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT) não têm
 *    árvore; são identificados pelo campo "single" com a URI completa.
 *
 *    Trabalhamos direto com DocumentsContract em vez de DocumentFile porque
 *    DocumentFile.fromTreeUri() reconstruído a partir da URI de um filho
 *    devolve a raiz da árvore, não o filho — um erro silencioso e difícil de
 *    rastrear.
 *
 * 2. IMPRESSÃO — recebe o HTML já montado pelo app, carrega num WebView
 *    invisível e entrega ao PrintManager do Android, que oferece "Salvar como
 *    PDF" em vetor, com texto selecionável.
 */
@CapacitorPlugin(name = "AskesisFs")
public class AskesisFsPlugin extends Plugin {

    // ────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────

    /** Resolve um handle (tree+doc, ou single) numa URI utilizável. */
    private Uri resolveUri(PluginCall call) {
        String single = call.getString("single");
        if (single != null && !single.isEmpty()) {
            return Uri.parse(single);
        }
        String tree = call.getString("tree");
        String doc = call.getString("doc");
        if (tree == null || doc == null) return null;
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), doc);
    }

    private static String mimeForName(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".md") || n.endsWith(".markdown")) return "text/markdown";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".rtf")) return "application/rtf";
        if (n.endsWith(".fodt")) return "application/vnd.oasis.opendocument.text-flat-xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    /** Monta o JSObject que o shim transforma num handle. */
    private static JSObject entry(String tree, String doc, String name, boolean isDir) {
        JSObject o = new JSObject();
        o.put("tree", tree);
        o.put("doc", doc);
        o.put("name", name);
        o.put("kind", isDir ? "directory" : "file");
        return o;
    }

    // ────────────────────────────────────────────────────────────
    //  Seletores
    // ────────────────────────────────────────────────────────────

    @PluginMethod
    public void pickDirectory(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, i, "onDirectoryPicked");
    }

    @ActivityCallback
    private void onDirectoryPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Seleção cancelada", "CANCELLED");
            return;
        }
        Uri treeUri = result.getData().getData();
        if (treeUri == null) {
            call.reject("Seleção cancelada", "CANCELLED");
            return;
        }
        try {
            getContext().getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // Alguns provedores não suportam permissão persistente; segue mesmo assim.
        }
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        String name = queryName(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId));
        JSObject res = entry(treeUri.toString(), docId, name != null ? name : "pasta", true);
        call.resolve(res);
    }

    @PluginMethod
    public void pickFiles(PluginCall call) {
        boolean multiple = Boolean.TRUE.equals(call.getBoolean("multiple", false));
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(call, i, "onFilesPicked");
    }

    @ActivityCallback
    private void onFilesPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            call.reject("Seleção cancelada", "CANCELLED");
            return;
        }
        JSArray files = new JSArray();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                Uri u = data.getClipData().getItemAt(k).getUri();
                files.put(singleEntry(u));
            }
        } else if (data.getData() != null) {
            files.put(singleEntry(data.getData()));
        }
        JSObject res = new JSObject();
        res.put("files", files);
        call.resolve(res);
    }

    private JSObject singleEntry(Uri u) {
        JSObject o = new JSObject();
        o.put("single", u.toString());
        String n = queryName(u);
        o.put("name", n != null ? n : "arquivo");
        o.put("kind", "file");
        return o;
    }

    @PluginMethod
    public void createFile(PluginCall call) {
        String suggested = call.getString("suggestedName", "arquivo.md");
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mimeForName(suggested));
        i.putExtra(Intent.EXTRA_TITLE, suggested);
        startActivityForResult(call, i, "onFileCreated");
    }

    @ActivityCallback
    private void onFileCreated(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) {
            call.reject("Seleção cancelada", "CANCELLED");
            return;
        }
        Uri u = result.getData().getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        call.resolve(singleEntry(u));
    }

    // ────────────────────────────────────────────────────────────
    //  Navegação na árvore
    // ────────────────────────────────────────────────────────────

    @PluginMethod
    public void listDir(PluginCall call) {
        String tree = call.getString("tree");
        String doc = call.getString("doc");
        if (tree == null || doc == null) {
            call.reject("Handle de pasta inválido", "INVALID");
            return;
        }
        Uri treeUri = Uri.parse(tree);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, doc);
        JSArray entries = new JSArray();
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String childId = c.getString(0);
                    String childName = c.getString(1);
                    String mime = c.getString(2);
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    entries.put(entry(tree, childId, childName, isDir));
                }
            }
        } catch (Exception e) {
            call.reject("Falha ao listar pasta: " + e.getMessage(), "IO");
            return;
        } finally {
            if (c != null) c.close();
        }
        JSObject res = new JSObject();
        res.put("entries", entries);
        call.resolve(res);
    }

    @PluginMethod
    public void getChild(PluginCall call) {
        String tree = call.getString("tree");
        String doc = call.getString("doc");
        String name = call.getString("name");
        String kind = call.getString("kind", "file");
        boolean create = Boolean.TRUE.equals(call.getBoolean("create", false));
        if (tree == null || doc == null || name == null) {
            call.reject("Parâmetros inválidos", "INVALID");
            return;
        }
        Uri treeUri = Uri.parse(tree);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, doc);

        // Procura pelo nome exibido
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    if (name.equals(c.getString(1))) {
                        boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                        call.resolve(entry(tree, c.getString(0), name, isDir));
                        return;
                    }
                }
            }
        } catch (Exception e) {
            call.reject("Falha ao procurar: " + e.getMessage(), "IO");
            return;
        } finally {
            if (c != null) c.close();
        }

        if (!create) {
            call.reject("Não encontrado: " + name, "NOT_FOUND");
            return;
        }

        try {
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc);
            String mime = "directory".equals(kind)
                    ? DocumentsContract.Document.MIME_TYPE_DIR
                    : mimeForName(name);
            Uri created = DocumentsContract.createDocument(
                    getContext().getContentResolver(), parentUri, mime, name);
            if (created == null) {
                call.reject("Não foi possível criar: " + name, "IO");
                return;
            }
            String newId = DocumentsContract.getDocumentId(created);
            call.resolve(entry(tree, newId, name, "directory".equals(kind)));
        } catch (Exception e) {
            call.reject("Falha ao criar: " + e.getMessage(), "IO");
        }
    }

    @PluginMethod
    public void removeEntry(PluginCall call) {
        Uri uri = resolveUri(call);
        if (uri == null) {
            call.reject("Handle inválido", "INVALID");
            return;
        }
        try {
            boolean ok = DocumentsContract.deleteDocument(getContext().getContentResolver(), uri);
            if (!ok) {
                call.reject("Não foi possível apagar", "IO");
                return;
            }
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject("Falha ao apagar: " + e.getMessage(), "IO");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Leitura e escrita
    // ────────────────────────────────────────────────────────────

    @PluginMethod
    public void readFile(PluginCall call) {
        Uri uri = resolveUri(call);
        if (uri == null) {
            call.reject("Handle inválido", "INVALID");
            return;
        }
        InputStream in = null;
        try {
            in = getContext().getContentResolver().openInputStream(uri);
            if (in == null) {
                call.reject("Não foi possível abrir o arquivo", "IO");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            JSObject res = new JSObject();
            res.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            String name = queryName(uri);
            res.put("name", name != null ? name : "arquivo");
            res.put("mimeType", mimeForName(name));
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Falha ao ler: " + e.getMessage(), "IO");
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        Uri uri = resolveUri(call);
        String data = call.getString("data");
        if (uri == null || data == null) {
            call.reject("Handle inválido", "INVALID");
            return;
        }
        OutputStream out = null;
        try {
            // "wt" trunca o conteúdo anterior — equivale ao createWritable() da web.
            out = getContext().getContentResolver().openOutputStream(uri, "wt");
            if (out == null) {
                call.reject("Não foi possível abrir para escrita", "IO");
                return;
            }
            out.write(Base64.decode(data, Base64.NO_WRAP));
            out.flush();
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject("Falha ao escrever: " + e.getMessage(), "IO");
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────
    //  Impressão / PDF
    // ────────────────────────────────────────────────────────────

    @PluginMethod
    public void printHtml(final PluginCall call) {
        final String html = call.getString("html", "");
        final String name = call.getString("name", "documento");
        final boolean waitForSignal = Boolean.TRUE.equals(call.getBoolean("waitForSignal", false));
        final Activity activity = getActivity();
        if (activity == null) {
            call.reject("Sem activity", "NO_ACTIVITY");
            return;
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final WebView wv = new WebView(activity);
                    wv.getSettings().setJavaScriptEnabled(true);
                    wv.getSettings().setAllowFileAccess(true);
                    wv.getSettings().setAllowFileAccessFromFileURLs(true);
                    wv.getSettings().setAllowUniversalAccessFromFileURLs(true);
                    wv.getSettings().setLoadWithOverviewMode(false);
                    wv.getSettings().setUseWideViewPort(false);

                    // INVISIBLE (não GONE): o Android mede e posiciona a view,
                    // que é o que o adaptador de impressão precisa, mas ela não
                    // aparece nem recebe toques.
                    wv.setVisibility(View.INVISIBLE);
                    final ViewGroup root = activity.findViewById(android.R.id.content);
                    root.addView(wv, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));

                    final PrintState state = new PrintState();

                    final Runnable fire = new Runnable() {
                        @Override
                        public void run() {
                            if (state.printed) return;
                            state.printed = true;
                            try {
                                PrintManager pm = (PrintManager) activity
                                        .getSystemService(Context.PRINT_SERVICE);
                                pm.print(name,
                                        wv.createPrintDocumentAdapter(name),
                                        new PrintAttributes.Builder().build());
                            } catch (Exception e) {
                                // nada a fazer: o diálogo de impressão não abriu
                            }
                            // O WebView precisa continuar vivo enquanto o serviço
                            // de impressão desenha as páginas.
                            wv.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try { root.removeView(wv); wv.destroy(); } catch (Exception ignored) {}
                                }
                            }, 180000);
                        }
                    };

                    // O Paged.js chama window.print() quando termina de paginar.
                    // O shim em JS redireciona essa chamada para cá, o que nos dá
                    // o momento exato em que o documento está pronto.
                    wv.addJavascriptInterface(new PrintBridge(activity, fire), "AndroidPrintBridge");

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            // Sem paginação: imprime assim que assentar.
                            // Com paginação: espera o sinal, com rede de segurança.
                            wv.postDelayed(fire, waitForSignal ? 20000 : 500);
                        }
                    });

                    wv.loadDataWithBaseURL("file:///android_asset/public/",
                            html, "text/html", "UTF-8", null);
                    call.resolve(new JSObject());
                } catch (Exception e) {
                    call.reject("Falha ao preparar impressão: " + e.getMessage(), "PRINT");
                }
            }
        });
    }

    /** Caixinha mutável: variáveis capturadas por classes anônimas precisam ser finais. */
    private static class PrintState {
        boolean printed = false;
    }

    /**
     * Objeto exposto ao JavaScript da página de impressão.
     *
     * Classe nomeada de propósito: addJavascriptInterface localiza o método por
     * reflexão, e um tipo nomeado e público é mais previsível do que uma classe
     * anônima — sobretudo se um dia a minificação for ligada.
     */
    public static class PrintBridge {
        private final Activity activity;
        private final Runnable onReady;

        PrintBridge(Activity activity, Runnable onReady) {
            this.activity = activity;
            this.onReady = onReady;
        }

        @JavascriptInterface
        public void ready() {
            activity.runOnUiThread(onReady);
        }
    }
}
