package com.mio.irondome.base.web

/** As cores da página, claras ou escuras como a IDE; [background] e [foreground] vêm do tema atual. */
data class HtmlTheme(val dark: Boolean, val background: String, val foreground: String)

/**
 * Os componentes das abas web, com o visual do Tomahawk IA: cabeçalho, cartões, botões, controles segmentados,
 * campos, avisos e a lista de execuções.
 */
object WebUi {

    /** Cabeçalho das abas: ícone num ladrilho, sobretítulo, título, linha de apoio, etiquetas e o selo à direita. */
    fun header(icon: String, kicker: String, title: String, subtitle: String?, chips: String = "", side: String = ""): String = buildString {
        append("<header class=\"top\"><div class=\"top-icon\">$icon</div><div class=\"top-body\">")
        append("<div class=\"kicker\">").append(kicker).append("</div>")
        append("<div class=\"top-title\">").append(title).append("</div>")
        subtitle?.let { append("<div class=\"top-sub\">").append(it).append("</div>") }
        if (chips.isNotEmpty()) append("<div class=\"chips\">").append(chips).append("</div>")
        append("</div>")
        if (side.isNotEmpty()) append("<div class=\"top-side\">").append(side).append("</div>")
        append("</header>")
    }

    fun chip(label: String, value: String, tone: String? = null): String {
        val cls = listOfNotNull("chip", tone?.let { "c-$it" }).joinToString(" ")
        return "<span class=\"$cls\"><b>$label</b>$value</span>"
    }

    fun button(text: String, cmd: String, kind: String = "", arg: String? = null, enabled: Boolean = true, title: String? = null): String =
        "<button class=\"btn $kind\" data-cmd=\"$cmd\"" + (arg?.let { " data-arg=\"${attr(it)}\"" } ?: "") +
            (title?.let { " title=\"${attr(it)}\"" } ?: "") + (if (enabled) "" else " disabled") + ">$text</button>"

    /** Controle segmentado: escolhe um valor de [options] (valor → rótulo); [send] manda a escolha na hora. */
    fun seg(name: String, options: List<Pair<String, String>>, selected: String?, send: String? = null): String = buildString {
        append("<div class=\"seg\"").append(send?.let { " data-send=\"$it\"" }.orEmpty()).append(">")
        append("<input type=\"hidden\" name=\"$name\" value=\"${attr(selected.orEmpty())}\">")
        options.forEach { (value, label) ->
            append("<button type=\"button\" data-value=\"${attr(value)}\"").append(if (value == selected) " class=\"on\"" else "").append(">$label</button>")
        }
        append("</div>")
    }

    /** Aviso com ícone: [tone] é `warn`, `info`, `ok` ou `err`; [html] já vem escapado. */
    fun notice(tone: String, icon: String, html: String): String =
        "<div class=\"notice $tone\"><span class=\"ni\">$icon</span><div class=\"nb\">$html</div></div>"

    fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Um texto seguro para atributo HTML entre aspas duplas. */
    fun attr(text: String) = esc(text)

    /** O CSS da página: as cores do tema, com [accent] como o tom dela (`teal`, `purple`, `blue`…), e os componentes. */
    fun css(theme: HtmlTheme, accent: String): String {
        fun c(light: String, dark: String) = if (theme.dark) dark else light
        return """
        :root{
          --bg:${theme.background};--fg:${theme.foreground};
          --muted:${c("#656D76", "#9198A1")};--border:${c("#D8DEE4", "#393B40")};
          --card:${c("#F6F8FA", "#2B2D30")};--code-bg:${c("#EFF2F5", "#26282C")};
          --shadow:${c("0 1px 2px rgba(31,35,40,.06)", "0 1px 2px rgba(0,0,0,.35)")};
          --green:${c("#2DA44E", "#3FB950")};--green-bg:${c("#DAFBE1", "#13361F")};--green-fg:${c("#116329", "#56D364")};
          --red:${c("#CF222E", "#F85149")};--red-bg:${c("#FFEBE9", "#4A1A1E")};--red-fg:${c("#A40E26", "#FF7B72")};
          --yellow:${c("#D4A72C", "#BB8009")};--yellow-bg:${c("#FFF1C2", "#4A3D12")};--yellow-fg:${c("#7A5A00", "#E3C065")};
          --blue:${c("#0969DA", "#2F81F7")};--blue-bg:${c("#DDF4FF", "#12304F")};--blue-fg:${c("#0550AE", "#79C0FF")};
          --purple:${c("#8250DF", "#A371F7")};--purple-bg:${c("#FBEFFF", "#3A2350")};--purple-fg:${c("#6639BA", "#D2A8FF")};
          --orange:${c("#BC4C00", "#DB6D28")};--orange-bg:${c("#FFF1E5", "#4A2A12")};--orange-fg:${c("#953800", "#FFA657")};
          --teal:${c("#1B7C83", "#39C5CF")};--teal-bg:${c("#D8F5F7", "#10373A")};--teal-fg:${c("#0A5A60", "#56D4DD")};
          --gray:${c("#8C959F", "#6E7681")};--gray-bg:${c("#EAEEF2", "#30363D")};--gray-fg:${c("#57606A", "#9198A1")};
          --accent:var(--$accent);--accent-bg:var(--$accent-bg);--accent-fg:var(--$accent-fg);
        }
        *{box-sizing:border-box}
        html,body{margin:0;background:var(--bg);color:var(--fg);font:13px/1.6 -apple-system,"Segoe UI",Inter,Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased}
        .wrap{max-width:1100px;margin:0 auto;padding:14px 14px 48px;min-width:0;overflow-wrap:anywhere}
        a{color:var(--blue-fg)}
        code{font-family:"JetBrains Mono",Consolas,monospace;font-size:12px;background:var(--code-bg);padding:1px 6px;border-radius:5px;word-break:break-word}
        .muted{color:var(--muted)}
        button{font-family:inherit}

        .top{position:relative;display:flex;gap:14px;align-items:center;padding:16px 18px;border-radius:16px;border:1px solid var(--border);overflow:hidden;
          background:radial-gradient(120% 140% at 0% 0%,var(--accent-bg) 0%,var(--card) 60%)}
        .top::after{content:"";position:absolute;right:-50px;bottom:-70px;width:180px;height:180px;border-radius:50%;background:var(--accent);opacity:.07}
        .top-icon{flex:none;width:48px;height:48px;border-radius:14px;display:flex;align-items:center;justify-content:center;font-size:24px;
          background:var(--bg);border:1px solid var(--border);box-shadow:var(--shadow)}
        .top-body{flex:1;min-width:0;position:relative;z-index:1}
        .kicker{font-size:10.5px;font-weight:800;letter-spacing:.1em;text-transform:uppercase;color:var(--accent-fg)}
        .top-title{font-size:19px;font-weight:800;line-height:1.25;margin-top:2px;overflow-wrap:anywhere}
        .top-sub{color:var(--muted);font-size:12.5px;margin-top:3px}
        .top-side{flex:none;position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;gap:4px}

        .chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:9px}
        .chip{display:inline-flex;gap:6px;align-items:center;padding:3px 10px;border-radius:999px;background:var(--bg);border:1px solid var(--border);font-size:12px;color:var(--fg);max-width:100%}
        .chip b{color:var(--muted);font-weight:600;white-space:nowrap}
        .chip.c-green{border-color:var(--green);color:var(--green-fg)} .chip.c-yellow{border-color:var(--yellow);color:var(--yellow-fg);background:var(--yellow-bg)}
        .chip.c-red{border-color:var(--red);color:var(--red-fg)} .chip.c-blue{border-color:var(--blue);color:var(--blue-fg)}
        .chip.c-orange{border-color:var(--orange);color:var(--orange-fg)} .chip.c-purple{border-color:var(--purple);color:var(--purple-fg)}

        .sec{display:flex;align-items:center;gap:8px;margin:20px 2px 10px;font-size:11px;font-weight:800;letter-spacing:.09em;text-transform:uppercase;color:var(--muted)}
        .sec > span{flex:none}
        .sec .line{flex:1;height:1px;background:var(--border)}

        .btn{display:inline-flex;align-items:center;gap:6px;height:30px;padding:0 13px;border-radius:9px;border:1px solid var(--border);background:var(--bg);
          color:var(--fg);font-size:12.5px;font-weight:600;cursor:pointer;transition:all .15s;white-space:nowrap}
        .btn:hover:not([disabled]){border-color:var(--accent);color:var(--accent-fg);transform:translateY(-1px)}
        .btn.primary{background:linear-gradient(135deg,var(--accent),var(--accent-fg));border-color:transparent;color:#fff;box-shadow:0 2px 10px -2px var(--accent)}
        .btn.primary:hover:not([disabled]){color:#fff;filter:brightness(1.08)}
        .btn.big{height:36px;padding:0 18px;font-size:13.5px;border-radius:11px}
        .btn.danger{border-color:var(--red);color:var(--red-fg)}
        .btn.ghost{background:transparent}
        .btn[disabled]{opacity:.45;cursor:not-allowed;box-shadow:none}
        .row-btns{display:flex;flex-wrap:wrap;gap:8px;align-items:center}

        .seg{display:inline-flex;padding:3px;border-radius:10px;background:var(--gray-bg);gap:2px;flex-wrap:wrap}
        .seg button{border:0;background:transparent;color:var(--muted);height:26px;padding:0 11px;border-radius:7px;font-size:12px;font-weight:600;cursor:pointer}
        .seg button.on{background:var(--bg);color:var(--fg);box-shadow:var(--shadow)}

        .card2{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:14px 16px;box-shadow:var(--shadow);min-width:0}
        .card2 + .card2{margin-top:12px}
        .card2 h3{margin:0 0 4px;font-size:14px;display:flex;align-items:center;gap:8px}
        .card2 .desc{color:var(--muted);font-size:12px;margin-bottom:10px}
        .grid2{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr));gap:12px}

        .notice{display:flex;gap:10px;align-items:flex-start;padding:10px 14px;border-radius:12px;border:1px solid;margin-top:12px}
        .notice .ni{font-size:16px;line-height:1.3}
        .notice .nb{flex:1;min-width:0}
        .notice.warn{background:var(--yellow-bg);border-color:var(--yellow);color:var(--yellow-fg)}
        .notice.info{background:var(--blue-bg);border-color:var(--blue);color:var(--blue-fg)}
        .notice.ok{background:var(--green-bg);border-color:var(--green);color:var(--green-fg)}
        .notice.err{background:var(--red-bg);border-color:var(--red);color:var(--red-fg)}

        .field{display:flex;flex-direction:column;gap:4px;margin-bottom:12px}
        .field label{font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:var(--muted)}
        .field .hint{font-size:11.5px;color:var(--muted)}
        .field .hint.bad{color:var(--red-fg)} .field .hint.good{color:var(--green-fg)}
        .in,select.in{width:100%;border:1px solid var(--border);background:var(--bg);color:var(--fg);border-radius:9px;padding:7px 10px;font:13px inherit;outline:none;transition:border .15s,box-shadow .15s}
        .in:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--accent-bg)}
        .in-row{display:flex;gap:8px} .in-row .in{flex:1;min-width:0}
        .cols{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(200px,100%),1fr));gap:0 12px}
        .status-line{font-size:12px;margin-top:8px;color:var(--muted)} .status-line.good{color:var(--green-fg)} .status-line.bad{color:var(--red-fg)}

        .run{display:flex;gap:12px;align-items:flex-start;padding:10px 12px;border-radius:12px;border:1px solid var(--border);background:var(--bg);cursor:pointer;transition:all .15s;margin-bottom:8px}
        .run:hover{border-color:var(--rc);transform:translateX(2px)}
        .run-icon{flex:none;width:30px;height:30px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:800;background:var(--rbg);color:var(--rc)}
        .run-body{flex:1;min-width:0}
        .run-title{font-weight:700;overflow-wrap:anywhere}
        .run-meta{color:var(--muted);font-size:11.5px;margin-top:2px;display:flex;gap:10px;flex-wrap:wrap}
        .r-ok{--rc:var(--green);--rbg:var(--green-bg)} .r-failed{--rc:var(--red);--rbg:var(--red-bg)}

        .caps{display:grid;gap:6px;margin:0;padding:0;list-style:none}
        .caps li{display:flex;gap:8px;align-items:flex-start;font-size:12.5px}
        .caps .ck{flex:none;width:18px;height:18px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:800}
        .caps .on{background:var(--green-bg);color:var(--green-fg)} .caps .off{background:var(--gray-bg);color:var(--muted)}
        .status-big{display:inline-flex;align-items:center;gap:8px;padding:6px 12px;border-radius:999px;font-weight:800;font-size:12.5px;border:1px solid;white-space:nowrap}
        .status-big.good{background:var(--green-bg);color:var(--green-fg);border-color:var(--green)}
        .status-big.warn{background:var(--yellow-bg);color:var(--yellow-fg);border-color:var(--yellow)}
        .status-big.bad{background:var(--gray-bg);color:var(--muted);border-color:var(--border)}
        .empty2{text-align:center;padding:20px;border:1px dashed var(--border);border-radius:14px;color:var(--muted)}
        @media (max-width:520px){ .top{flex-wrap:wrap} .top-side{flex-direction:row} }
        """.trimIndent()
    }

    /**
     * A ponte da página com o plugin: cliques em `data-cmd`, controles segmentados, mudanças em `data-change`, o
     * foco nos campos (para não redesenhar enquanto se digita) e a rolagem, preservada entre redesenhos.
     */
    fun script(bridge: String, scrollY: Int): String = """
        (function(){
          var raw = $bridge;
          var dome = window.dome = {
            send: function(c, data){ var m = data || {}; m.c = c; raw(JSON.stringify(m)); },
            form: function(el){
              var root = el.closest('[data-form]'); var out = {};
              if (!root) return out;
              root.querySelectorAll('[name]').forEach(function(i){
                if (i.type === 'radio') { if (i.checked) out[i.name] = i.value; }
                else if (i.type === 'checkbox') out[i.name] = i.checked;
                else out[i.name] = i.value;
              });
              return out;
            }
          };
          document.addEventListener('click', function(e){
            var seg = e.target.closest('.seg button');
            if (seg) {
              e.preventDefault();
              var box = seg.closest('.seg');
              box.querySelectorAll('button').forEach(function(b){ b.classList.toggle('on', b === seg); });
              var input = box.querySelector('input[type=hidden]'); if (input) input.value = seg.getAttribute('data-value');
              if (box.getAttribute('data-send')) { var d = dome.form(box); d.value = seg.getAttribute('data-value'); dome.send(box.getAttribute('data-send'), d); }
              return;
            }
            var el = e.target.closest('[data-cmd]');
            if (!el || el.disabled) return;
            if (e.target.closest('input,select') && el.tagName !== 'BUTTON') return;
            e.preventDefault();
            var data = dome.form(el);
            var arg = el.getAttribute('data-arg'); if (arg !== null) data.arg = arg;
            dome.send(el.getAttribute('data-cmd'), data);
          });
          document.addEventListener('change', function(e){
            var el = e.target.closest('[data-change]'); if (!el) return;
            var data = dome.form(el); data.value = el.value; dome.send(el.getAttribute('data-change'), data);
          });
          document.addEventListener('focusin', function(e){ if (e.target.matches('input:not([type=hidden]),select')) dome.send('focus'); });
          document.addEventListener('focusout', function(e){ if (e.target.matches('input:not([type=hidden]),select')) setTimeout(function(){
            if (!document.activeElement || !document.activeElement.matches('input,select')) dome.send('blur');
          }, 60); });
          var timer;
          window.addEventListener('scroll', function(){ clearTimeout(timer); timer = setTimeout(function(){ dome.send('scroll', { y: Math.round(window.scrollY) }); }, 150); });
          window.scrollTo(0, $scrollY);
        })();
    """.trimIndent()
}
