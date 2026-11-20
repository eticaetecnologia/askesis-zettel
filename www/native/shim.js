/* ============================================================
   Askesis-Zettel — camada de compatibilidade nativa
   ------------------------------------------------------------
   Carregado antes do app. Fora do Capacitor não faz nada: o app
   continua sendo o mesmo PWA de sempre.

   Dentro do APK, emula as APIs de navegador das quais o app
   depende e que o WebView do Android não oferece:

     - File System Access API (showDirectoryPicker e companhia),
       reproduzida sobre o Storage Access Framework
     - download via <a download>, redirecionado para um seletor
       de destino nativo
     - window.open + print(), redirecionado para o PrintManager

   A intenção é que nenhuma linha do app precise saber disso.
   ============================================================ */
(function () {
  "use strict";

  var C = window.Capacitor;
  if (!C || typeof C.isNativePlatform !== "function" || !C.isNativePlatform()) return;

  var Native = (C.registerPlugin ? C.registerPlugin("AskesisFs") : null)
            || (C.Plugins && C.Plugins.AskesisFs);
  if (!Native) {
    console.warn("[askesis] ponte nativa indisponível; seguindo sem ela");
    return;
  }

  window.__ASKESIS_NATIVE__ = true;

  // ── Base64 ────────────────────────────────────────────────
  function b64ToBytes(b64) {
    var bin = atob(b64), u = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
    return u;
  }
  function bytesToB64(u8) {
    var s = "", CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    }
    return btoa(s);
  }
  function blobToB64(blob) {
    return blob.arrayBuffer().then(function (ab) { return bytesToB64(new Uint8Array(ab)); });
  }

  // ── Erros com os nomes que o app espera ───────────────────
  // O app faz `if (e.name !== "AbortError")` em vários pontos; se
  // o cancelamento não chegar com esse nome, ele mostra um alerta
  // de erro cada vez que o usuário desiste de um diálogo.
  function named(name, msg) {
    var e = new Error(msg || name);
    e.name = name;
    return e;
  }
  function mapError(err) {
    var code = (err && (err.code || err.errorMessage)) || "";
    var msg = (err && err.message) || String(err);
    if (code === "CANCELLED" || /cancel/i.test(msg)) return named("AbortError", "Cancelado");
    if (code === "NOT_FOUND") return named("NotFoundError", msg);
    return named("InvalidStateError", msg);
  }
  function call(method, args) {
    return Native[method](args || {}).catch(function (e) { throw mapError(e); });
  }

  // ── Handles ───────────────────────────────────────────────
  function makeHandle(d) {
    return d.kind === "directory" ? new NDirHandle(d) : new NFileHandle(d);
  }
  function ref(h) {
    return h._single ? { single: h._single } : { tree: h._tree, doc: h._doc };
  }

  function NFileHandle(d) {
    this.kind = "file";
    this.name = d.name;
    this._tree = d.tree;
    this._doc = d.doc;
    this._single = d.single;
  }
  NFileHandle.prototype.getFile = function () {
    var self = this;
    return call("readFile", ref(this)).then(function (r) {
      return new File([b64ToBytes(r.data)], self.name || r.name || "arquivo", {
        type: r.mimeType || "",
        lastModified: Date.now()
      });
    });
  };
  NFileHandle.prototype.createWritable = function () {
    var self = this, parts = [];
    return Promise.resolve({
      write: function (chunk) {
        // O app sempre escreve de uma vez e fecha; acumular e gravar
        // no close() mantém a escrita atômica.
        if (chunk && typeof chunk === "object" && "data" in chunk) chunk = chunk.data;
        parts.push(chunk);
        return Promise.resolve();
      },
      truncate: function () { parts = []; return Promise.resolve(); },
      seek: function () { return Promise.resolve(); },
      abort: function () { parts = []; return Promise.resolve(); },
      close: function () {
        return blobToB64(new Blob(parts)).then(function (data) {
          return call("writeFile", Object.assign(ref(self), { data: data }));
        });
      }
    });
  };
  NFileHandle.prototype.requestPermission = function () { return Promise.resolve("granted"); };
  NFileHandle.prototype.queryPermission = function () { return Promise.resolve("granted"); };
  NFileHandle.prototype.isSameEntry = function (o) {
    return Promise.resolve(!!o && o._doc === this._doc && o._single === this._single);
  };

  function NDirHandle(d) {
    this.kind = "directory";
    this.name = d.name;
    this._tree = d.tree;
    this._doc = d.doc;
  }
  NDirHandle.prototype._list = function () {
    var tree = this._tree;
    return call("listDir", { tree: tree, doc: this._doc }).then(function (r) {
      return (r.entries || []).map(function (e) {
        e.tree = e.tree || tree;
        return makeHandle(e);
      });
    });
  };
  // entries/values/keys precisam ser iteradores assíncronos: o app usa
  // `for await (const [name, handle] of dirHandle.entries())`.
  NDirHandle.prototype.entries = function () {
    var p = this._list(), i = 0, items = null;
    var it = {
      next: function () {
        return p.then(function (list) {
          items = list;
          if (i >= items.length) return { done: true, value: undefined };
          var h = items[i++];
          return { done: false, value: [h.name, h] };
        });
      }
    };
    it[Symbol.asyncIterator] = function () { return it; };
    return it;
  };
  NDirHandle.prototype.values = function () {
    var inner = this.entries();
    var it = {
      next: function () {
        return inner.next().then(function (r) {
          return r.done ? r : { done: false, value: r.value[1] };
        });
      }
    };
    it[Symbol.asyncIterator] = function () { return it; };
    return it;
  };
  NDirHandle.prototype.keys = function () {
    var inner = this.entries();
    var it = {
      next: function () {
        return inner.next().then(function (r) {
          return r.done ? r : { done: false, value: r.value[0] };
        });
      }
    };
    it[Symbol.asyncIterator] = function () { return it; };
    return it;
  };
  NDirHandle.prototype[Symbol.asyncIterator] = function () { return this.entries(); };

  NDirHandle.prototype.getDirectoryHandle = function (name, opts) {
    return call("getChild", {
      tree: this._tree, doc: this._doc, name: name,
      kind: "directory", create: !!(opts && opts.create)
    }).then(makeHandle);
  };
  NDirHandle.prototype.getFileHandle = function (name, opts) {
    return call("getChild", {
      tree: this._tree, doc: this._doc, name: name,
      kind: "file", create: !!(opts && opts.create)
    }).then(makeHandle);
  };
  NDirHandle.prototype.removeEntry = function (name) {
    var self = this;
    return this.getFileHandle(name).catch(function () {
      return self.getDirectoryHandle(name);
    }).then(function (h) {
      return call("removeEntry", ref(h));
    });
  };
  NDirHandle.prototype.requestPermission = function () { return Promise.resolve("granted"); };
  NDirHandle.prototype.queryPermission = function () { return Promise.resolve("granted"); };

  // ── Seletores ─────────────────────────────────────────────
  window.showDirectoryPicker = function () {
    return call("pickDirectory").then(makeHandle);
  };
  window.showOpenFilePicker = function (opts) {
    return call("pickFiles", { multiple: !!(opts && opts.multiple) }).then(function (r) {
      return (r.files || []).map(makeHandle);
    });
  };
  window.showSaveFilePicker = function (opts) {
    return call("createFile", {
      suggestedName: (opts && opts.suggestedName) || "arquivo.md"
    }).then(makeHandle);
  };

  // ── Download → seletor de destino nativo ──────────────────
  // O app exporta com <a download> + click(). Interceptamos só
  // âncoras que tenham o atributo download e uma URL blob:/data:,
  // para não atrapalhar nenhum outro clique.
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var dl = this.getAttribute && this.getAttribute("download");
    var href = this.href || "";
    if (dl && (href.indexOf("blob:") === 0 || href.indexOf("data:") === 0)) {
      fetch(href)
        .then(function (r) { return r.blob(); })
        .then(function (b) {
          return blobToB64(b).then(function (data) {
            return call("createFile", { suggestedName: dl }).then(function (h) {
              return call("writeFile", Object.assign(ref(makeHandle(h)), { data: data }));
            });
          });
        })
        .catch(function (e) {
          if (e.name !== "AbortError") alert("Erro ao salvar: " + e.message);
        });
      return;
    }
    return origClick.apply(this, arguments);
  };

  // ── window.open + print → PrintManager ────────────────────
  // O app monta o HTML de impressão numa janela em branco e manda
  // imprimir. Aqui a "janela" só coleta o HTML e entrega ao nativo.
  var origOpen = window.open;
  window.open = function (url) {
    var blank = (url === "" || url == null || url === "about:blank");
    if (!blank) return origOpen.apply(window, arguments);

    var buf = "", sent = false;

    function send() {
      if (sent) return;
      sent = true;
      // Se o documento usa Paged.js, ele avisa que terminou de paginar
      // chamando window.print(). Redirecionamos essa chamada para a
      // ponte, e é ela que dispara a impressão na hora certa.
      var usesPaged = /PagedConfig|paged\.polyfill/.test(buf);
      var hook = "<script>window.print=function(){try{AndroidPrintBridge.ready();}catch(e){}};</scr"
               + "ipt>";
      var html = buf.indexOf("</head>") !== -1
        ? buf.replace("</head>", hook + "</head>")
        : hook + buf;
      call("printHtml", { html: html, name: "askesis", waitForSignal: usesPaged })
        .catch(function (e) { alert("Erro ao imprimir: " + e.message); });
    }

    return {
      document: {
        write: function (s) { buf += s; },
        writeln: function (s) { buf += s + "\n"; },
        close: function () { setTimeout(send, 0); },
        open: function () {}
      },
      focus: function () {},
      close: function () {},
      print: function () { send(); },
      onload: null,
      closed: false
    };
  };

  // ── Service worker ────────────────────────────────────────
  // Os arquivos já vivem dentro do APK. Um SW aqui só acrescentaria
  // uma camada de cache capaz de servir uma versão velha do app.
  try {
    if (navigator.serviceWorker) {
      navigator.serviceWorker.register = function () { return Promise.resolve({}); };
    }
  } catch (e) { /* alguns WebViews não deixam sobrescrever */ }

  console.log("[askesis] ponte nativa ativa");
})();
