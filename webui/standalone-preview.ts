/**
 * 一次性文件预览页：每个文件一个独立标签页，可同时开多个、来回切换。
 *
 * 为什么不用底部弹层：弹层同一时间只能看一个文件，没法对照着看两个文件。
 * 为什么不用新路由：预览页不需要 WS、不需要 token —— 内容在父页读好后内联进
 * 一个自包含 HTML，关闭即失效，因此一个临时标签页就够，不给主进程加路由面。
 *
 * 弹窗拦截：window.open 必须在用户点击的同步栈里调用；异步读文件之后的
 * document.write 是对已打开的窗口写内容，不受手势限制。
 */
import { fileNameOf } from './file-preview'

export type StandalonePreviewContent =
  | { kind: 'image'; dataUrl: string }
  | { kind: 'pdf'; dataUrl: string }
  | {
      kind: 'markdown'
      /** Markdown 原文：「原文」视图与复制全文用 */
      text: string
      /** 父页用与会话正文同一套插件渲染好的静态 HTML */
      html: string
      truncated: boolean
      /**
       * 正文含公式时才带：预览页是独立文档，继承不到父页样式，
       * 少了 KaTeX 样式公式会散成乱码（字体仍从同源 /webui/assets 按需加载）。
       */
      katexCss?: string
    }
  | { kind: 'text'; text: string; truncated: boolean }
  | { kind: 'binary'; dataUrl: string }
  | { kind: 'error'; message: string }

/** HTML 转义：文件名与正文都来自用户文件，不能原样拼进页面 */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 下载脚本：iOS Safari 忽略 data URL 上 <a download> 的文件名（会存成「下载」无扩展名）。
 * 转成 Blob URL 后同源，download 文件名才生效。只有用户点下载时才执行。
 */
const DOWNLOAD_SCRIPT = `<script>
function proferDownload(){
  var a=document.getElementById('profer-src');
  if(!a)return;
  var href=a.getAttribute('data-href'), name=a.getAttribute('data-name');
  try{
    var comma=href.indexOf(','), meta=href.slice(0,comma), b64=href.slice(comma+1);
    var mime=(meta.match(/^data:([^;]+)/)||[])[1]||'application/octet-stream';
    var bin=atob(b64), bytes=new Uint8Array(bin.length);
    for(var i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);
    var url=URL.createObjectURL(new Blob([bytes],{type:mime}));
    var link=document.createElement('a');link.href=url;link.download=name;
    document.body.appendChild(link);link.click();link.remove();
    setTimeout(function(){URL.revokeObjectURL(url)},60000);
  }catch(e){alert('下载失败：'+e.message)}
}
<\/script>`

/**
 * PDF 内嵌升级脚本：把 data URL 换成 Blob URL 再喂给 <object>。
 *
 * - 部分浏览器对 data URL 里的大段 base64 直接走下载，不交给内置 PDF 查看器；
 * - 手机浏览器（iOS Safari / Android Chrome）都不渲染内嵌 PDF：预览标签页本身
 *   就是新标签页，直接跳过去交给系统 PDF 查看器，省掉「先提示无法内嵌、再点打开」。
 * Blob URL 只在当前预览标签内有效，不落盘、不联网。
 */
const PDF_BLOB_SCRIPT = `<script>
(function(){
  var src=document.getElementById('profer-src');
  var object=document.getElementById('profer-pdf');
  if(!src||!object)return;
  var dataUrl=src.getAttribute('data-href')||'';
  var comma=dataUrl.indexOf(',');
  if(comma<0)return;
  var meta=dataUrl.slice(0,comma);
  var mime=(meta.match(/^data:([^;]+)/)||[])[1]||'application/pdf';
  var url='';
  try{
    var bin=atob(dataUrl.slice(comma+1));
    var bytes=new Uint8Array(bin.length);
    for(var i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);
    url=URL.createObjectURL(new Blob([bytes],{type:mime}));
  }catch(e){return}
  var mobile=/Android|iPad|iPhone|iPod|Mobile/i.test(navigator.userAgent)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
  if(mobile){
    location.replace(url);
    return;
  }
  object.setAttribute('data',url);
  var links=document.querySelectorAll('.js-open-pdf');
  for(var j=0;j<links.length;j++)links[j].setAttribute('href',url);
})();
<\/script>`

/**
 * Markdown 预览脚本：预览 / 原文切换 + 复制全文。
 *
 * 复制顺序与 webui/clipboard.ts 一致：WebUI 常跑在 `http://192.168.x.x` 这类非安全
 * 上下文里，`navigator.clipboard` 可能不存在，所以先在点击的同步栈里试 execCommand，
 * 失败再走异步剪贴板 API。
 */
const MARKDOWN_SCRIPT = `<script>
function proferSetView(view){
  var preview=document.getElementById('profer-md-preview');
  var raw=document.getElementById('profer-md-raw');
  var pv=document.getElementById('profer-view-preview');
  var rw=document.getElementById('profer-view-raw');
  if(!preview||!raw||!pv||!rw)return;
  var showRaw=view==='raw';
  preview.hidden=showRaw;
  raw.hidden=!showRaw;
  pv.classList.toggle('on',!showRaw);
  rw.classList.toggle('on',showRaw);
  pv.setAttribute('aria-pressed',showRaw?'false':'true');
  rw.setAttribute('aria-pressed',showRaw?'true':'false');
}
function proferCopy(){
  var raw=document.getElementById('profer-md-raw');
  var btn=document.getElementById('profer-copy');
  if(!raw)return;
  var text=raw.textContent||'';
  function done(ok){
    if(!btn)return;
    var label=btn.textContent;
    btn.textContent=ok?'已复制':'复制失败';
    setTimeout(function(){btn.textContent=label},1600);
  }
  try{
    var area=document.createElement('textarea');
    area.value=text;area.setAttribute('readonly','');
    area.style.position='fixed';area.style.top='-1000px';area.style.opacity='0';
    document.body.appendChild(area);
    area.select();area.setSelectionRange(0,text.length);
    var ok=document.execCommand('copy');
    document.body.removeChild(area);
    if(ok){done(true);return}
  }catch(e){}
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(text).then(function(){done(true)},function(){done(false)});
    return;
  }
  done(false);
}
<\/script>`

/** 生成一个自包含的预览 HTML；脚本只管本地交互（下载 / PDF 内嵌 / Markdown 切换与复制），不连 WS、不带 token */export function buildPreviewDocument(name: string, content: StandalonePreviewContent): string {
  const title = escapeHtml(name)
  // data URL 与文件名放进隐藏节点的属性，脚本读取（避免拼进 JS 字符串被引号截断）
  const source = 'dataUrl' in content
    ? `<span id="profer-src" hidden data-href="${content.dataUrl}" data-name="${title}"></span>`
    : ''
  const downloadButton = '<button class="dl" type="button" onclick="proferDownload()">下载文件</button>'
  const openPdfButton = '<a class="dl js-open-pdf" href="#" target="_blank" rel="noopener">在新标签页打开</a>'
  // 视图切换与复制只对 Markdown 有意义，其它类型不出现这两个按钮
  const markdownControls = content.kind === 'markdown'
    ? '<span class="seg" role="group" aria-label="显示方式">'
      + '<button type="button" id="profer-view-preview" class="on" aria-pressed="true" onclick="proferSetView(\'preview\')">预览</button>'
      + '<button type="button" id="profer-view-raw" aria-pressed="false" onclick="proferSetView(\'raw\')">原文</button>'
      + '</span>'
      + '<button type="button" id="profer-copy" onclick="proferCopy()">复制全文</button>'
    : ''
  let body: string
  switch (content.kind) {
    case 'image':
      body = `<div class="stage"><img src="${content.dataUrl}" alt="${title}"></div>`
      break
    case 'pdf':
      body = `<object class="pdf" id="profer-pdf" data="${content.dataUrl}" type="application/pdf" aria-label="${title}">`
        + `<div class="fallback">浏览器无法内嵌显示此 PDF。${openPdfButton}${downloadButton}</div>`
        + `</object>`
        + `<div class="dock">${downloadButton}</div>`
      break
    case 'markdown':
      // html 由 react-markdown 生成：不渲染原始 HTML、链接协议经过过滤，可以按可信片段内联
      body = `<div class="md-preview" id="profer-md-preview">${content.html}</div>`
        + `<pre class="text" id="profer-md-raw" hidden>${escapeHtml(content.text)}</pre>`
        + (content.truncated ? '<div class="note">仅显示前 256 KB</div>' : '')
      break
    case 'text':
      body = `<pre class="text">${escapeHtml(content.text)}</pre>`
        + (content.truncated ? '<div class="note">仅显示前 256 KB</div>' : '')
      break
    case 'binary':
      body = `<div class="fallback">此文件格式无法在网页内预览。<br>${downloadButton}</div>`
      break
    case 'error':
      body = `<div class="fallback">打不开：${escapeHtml(content.message)}</div>`
      break
  }
  return `<!doctype html>
<html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<style>
:root{color-scheme:light dark;--bg:#191715;--panel:#211F1C;--ink:#ECEBE9;--muted:#AAA7A1;--line:#312F2B;--accent:#8FB8D4;--note:#DDB271;--code-bg:#14110F;--code-fg:#E7E2DA;--code-comment:#8A857D;--code-keyword:#C9A2D6;--code-string:#9ECB9B;--code-number:#E0B273;--code-title:#8FB8D4;--code-type:#D9C48F;--code-builtin:#8FB8D4;--code-attr:#D6A86B;--code-meta:#C2A6C9;--code-tag:#E0A090;--code-add:#9ECB9B;--code-del:#E89B91}
@media (prefers-color-scheme: light){:root{--bg:#F7F5F2;--panel:#FFFDFC;--ink:#322F2A;--muted:#66635C;--line:#E7E1D9;--accent:#3A6B8A;--note:#8A5F1F;--code-bg:#F2EEE8;--code-fg:#322F2A;--code-comment:#6F695F;--code-keyword:#8A3F6D;--code-string:#2F6B45;--code-number:#8A5F1F;--code-title:#2F6183;--code-type:#6A4F8A;--code-builtin:#2F6183;--code-attr:#8A4A3F;--code-meta:#6F4F7A;--code-tag:#9C3A46;--code-add:#2F6B45;--code-del:#A33A32}}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--ink);font:14px/1.6 system-ui,-apple-system,sans-serif}
.bar{height:44px;display:flex;align-items:center;gap:12px;padding:0 14px;border-bottom:1px solid var(--line);background:var(--panel);position:sticky;top:0}
.bar b{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:500;font-size:13px}
.bar button{padding:4px 10px;border:1px solid var(--line);border-radius:8px;background:transparent;color:var(--muted);font:inherit;font-size:12px;cursor:pointer;flex:none;white-space:nowrap}
.bar .seg{display:flex;border:1px solid var(--line);border-radius:8px;overflow:hidden;flex:none}
.bar .seg button{padding:4px 10px;border:0;border-radius:0}
.bar .seg button+button{border-left:1px solid var(--line)}
.bar .seg button.on{background:color-mix(in srgb,var(--accent) 16%,transparent);color:var(--accent)}
.stage{display:flex;justify-content:center;padding:12px}.stage img{max-width:100%;max-height:calc(100vh - 68px);object-fit:contain;border-radius:8px}
.pdf{display:block;width:100%;height:calc(100vh - 44px);border:0}
.text{margin:0;padding:14px;white-space:pre-wrap;overflow-wrap:anywhere;color:var(--ink);font:12.5px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace}
.note{padding:10px 14px;color:var(--note);font-size:12px}
.fallback{padding:40px 20px;text-align:center;color:var(--muted)}
.dock{padding:8px 14px;text-align:center}
.dl{display:inline-block;margin:8px;padding:8px 16px;border:1px solid var(--accent);border-radius:999px;background:transparent;color:var(--accent);font:inherit;font-size:12px;cursor:pointer;text-decoration:none}
.md-preview{padding:16px 18px 40px;max-width:860px;margin:0 auto;font-size:15px;line-height:1.62;overflow-wrap:anywhere}
.md-preview>:first-child{margin-top:0}.md-preview>:last-child{margin-bottom:0}
.md-preview p{margin:0 0 12px}
.md-preview h1,.md-preview h2,.md-preview h3,.md-preview h4,.md-preview h5,.md-preview h6{margin:24px 0 8px;font-weight:600;line-height:1.35}
.md-preview h1{font-size:22px}.md-preview h2{font-size:19px}.md-preview h3{font-size:16.5px}
.md-preview h4,.md-preview h5,.md-preview h6{font-size:15px;color:var(--muted)}
.md-preview ul,.md-preview ol{margin:0 0 12px;padding-left:22px}
.md-preview li{margin:4px 0}.md-preview li>p{margin:0}
.md-preview .task-list-item{list-style:none}
.md-preview .task-list-item input{margin:0 6px 0 -20px;vertical-align:middle}
.md-preview a{color:var(--accent);text-decoration:none;border-bottom:1px solid color-mix(in srgb,var(--accent) 35%,transparent)}
.md-preview blockquote{margin:0 0 12px;padding:2px 0 2px 12px;border-left:2px solid var(--line);color:var(--muted)}
.md-preview hr{margin:20px 0;border:0;border-top:1px solid var(--line)}
.md-preview pre{margin:0 0 12px;padding:12px;overflow-x:auto;border:1px solid var(--line);border-radius:12px;background:var(--code-bg);color:var(--code-fg);font:12.5px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace;scrollbar-width:none}
.md-preview pre::-webkit-scrollbar{display:none}
.md-preview code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12.5px}
.md-preview .hljs-comment,.md-preview .hljs-quote{color:var(--code-comment);font-style:italic}
.md-preview .hljs-keyword,.md-preview .hljs-selector-tag,.md-preview .hljs-doctag,.md-preview .hljs-formula,.md-preview .hljs-template-tag{color:var(--code-keyword)}
.md-preview .hljs-string,.md-preview .hljs-regexp,.md-preview .hljs-char.escape_{color:var(--code-string)}
.md-preview .hljs-number,.md-preview .hljs-literal,.md-preview .hljs-symbol,.md-preview .hljs-bullet{color:var(--code-number)}
.md-preview .hljs-title,.md-preview .hljs-title.function_,.md-preview .hljs-section{color:var(--code-title)}
.md-preview .hljs-type,.md-preview .hljs-title.class_,.md-preview .hljs-class .hljs-title{color:var(--code-type)}
.md-preview .hljs-built_in,.md-preview .hljs-builtin-name{color:var(--code-builtin)}
.md-preview .hljs-attr,.md-preview .hljs-attribute,.md-preview .hljs-variable,.md-preview .hljs-template-variable,.md-preview .hljs-property{color:var(--code-attr)}
.md-preview .hljs-meta,.md-preview .hljs-meta .hljs-keyword{color:var(--code-meta)}
.md-preview .hljs-tag,.md-preview .hljs-name,.md-preview .hljs-selector-id,.md-preview .hljs-selector-class{color:var(--code-tag)}
.md-preview .hljs-addition{color:var(--code-add)}
.md-preview .hljs-deletion{color:var(--code-del)}
.md-preview .hljs-emphasis{font-style:italic}
.md-preview .hljs-strong{font-weight:600}
.md-preview .hljs-link{text-decoration:underline}
.md-preview :not(pre)>code{padding:1px 5px;border-radius:6px;background:color-mix(in srgb,var(--ink) 10%,transparent);color:var(--ink)}
.md-preview table{display:block;width:max-content;max-width:100%;margin:0 0 12px;overflow-x:auto;border-collapse:collapse;font-size:13px;scrollbar-width:none}
.md-preview table::-webkit-scrollbar{display:none}
.md-preview th,.md-preview td{padding:6px 10px;border:1px solid var(--line);text-align:left}
.md-preview th{background:var(--panel);font-weight:600}
.md-preview img{max-width:100%;border-radius:8px}
.md-preview .katex-display{max-width:100%;overflow-x:auto;overflow-y:hidden;padding-bottom:2px;scrollbar-width:none}
.md-preview .katex-display::-webkit-scrollbar{display:none}
</style>${content.kind === 'markdown' && content.katexCss ? `<style>${content.katexCss}</style>` : ''}</head><body>
<div class="bar"><b>${title}</b>${markdownControls}<button type="button" onclick="window.close()">关闭</button></div>
${body}
${source}
${DOWNLOAD_SCRIPT}
${content.kind === 'markdown' ? MARKDOWN_SCRIPT : ''}
${content.kind === 'pdf' ? PDF_BLOB_SCRIPT : ''}
</body></html>`
}

/**
 * 打开一次性预览标签页。返回是否被浏览器允许打开（false = 弹窗被拦截）。
 * load 在 window.open 之后异步执行，结果写进刚开的窗口。
 */
export function openStandalonePreview(
  filePath: string,
  load: (path: string) => Promise<StandalonePreviewContent>,
): boolean {
  // 必须在点击的同步栈里开窗，否则浏览器（尤其 Safari）会拦截
  const win = window.open('', '_blank')
  if (!win) return false
  const name = fileNameOf(filePath)
  try {
    win.document.write('<!doctype html><meta charset="utf-8"><title>预览</title><style>:root{color-scheme:light dark;--bg:#191715;--muted:#AAA7A1}@media (prefers-color-scheme: light){:root{--bg:#F7F5F2;--muted:#66635C}}</style><body style="background:var(--bg);color:var(--muted);font:14px system-ui;padding:40px">正在读取…</body>')
    win.document.close()
  } catch {
    // 某些浏览器对 about:blank 写入有限制，窗口仍开着，下面继续尝试
  }
  void load(filePath).then((content) => {
    try {
      win.document.open()
      win.document.write(buildPreviewDocument(name, content))
      win.document.close()
    } catch {
      // 写入失败时用户至少还有一个空白标签页可以关掉，不影响父页面
    }
  }).catch((error: unknown) => {
    try {
      const message = error instanceof Error ? error.message : String(error)
      win.document.open()
      win.document.write(buildPreviewDocument(name, { kind: 'error', message }))
      win.document.close()
    } catch { /* 同上 */ }
  })
  return true
}
