/* Ponte do aplicativo de celular: faz o editor falar com o Android.
   Só age quando a ponte nativa (AndroidJFP) existe; no navegador comum não faz nada. */
(function () {
  'use strict';
  var A = window.AndroidJFP;
  if (!A) return;

  document.body.classList.add('mobile');

  var pending = {};
  var seq = 0;
  var closeCb = null;

  function call(start) {
    return new Promise(function (resolve) {
      var id = 'c' + (++seq);
      pending[id] = resolve;
      try { start(id); } catch (e) { delete pending[id]; resolve(null); }
    });
  }

  function toBase64(data) {
    var bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    var out = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(out);
  }

  var MIME = {
    docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    epub: 'application/epub+zip',
    md: 'text/markdown',
    zip: 'application/zip',
    pdf: 'application/pdf'
  };

  window.JFPMobile = {
    // Chamado pelo Android com a resposta de uma operação.
    resolve: function (id, json) {
      var done = pending[id];
      if (!done) return;
      delete pending[id];
      var value = null;
      try { value = json == null ? null : JSON.parse(json); } catch (e) { value = null; }
      done(value);
    },
    // Botão Voltar: fecha janela aberta ou o painel de revisão. Devolve true se tratou.
    back: function () {
      var dlg = document.querySelector('dialog[open]');
      if (dlg) { dlg.close(); return true; }
      var review = document.getElementById('review');
      if (review && !review.hidden && window.matchMedia('(max-width: 860px)').matches) {
        var btn = document.querySelector('[data-act="review"]');
        if (btn) btn.click(); else review.hidden = true;
        return true;
      }
      return false;
    },
    saveAndClose: function () { if (closeCb) closeCb(); }
  };

  window.jfpDesktop = {
    mobile: true,
    startupFile: function () { return Promise.resolve(null); },
    open: function () { return call(function (id) { A.open(id); }); },
    save: function (o) {
      return call(function (id) { A.save(id, o.path || '', o.content || '', o.suggestedName || 'Documento'); });
    },
    setDirty: function (flag) { A.setDirty(!!flag); },
    setTitle: function () {},
    closeNow: function () { A.closeNow(); },
    onSaveAndClose: function (cb) { closeCb = cb; },
    printPdf: function (o) { A.print(((o && o.suggestedName) || 'Documento').replace(/\.jfpw$/i, '')); },
    exportFile: function (o) {
      var ext = o.ext || 'bin';
      var name = (o.suggestedName || 'Documento') + '.' + ext;
      var b64 = toBase64(o.data);
      return call(function (id) { A.exportFile(id, name, MIME[ext] || 'application/octet-stream', b64); });
    },
    spellStatus: function () { return Promise.resolve('none'); },
    spellCheck: function () { return Promise.resolve([]); }
  };
})();
