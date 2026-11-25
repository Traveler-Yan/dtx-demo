#!/usr/bin/env python3
"""Build docs/分布式事务详解.pdf from the Markdown sources in docs/src.

Pipeline: markdown -> strict XHTML (with print CSS) -> Flying Saucer -> PDF.
The Java side lives in tools/pdfgen (see HtmlToPdf.java).
"""

from __future__ import annotations

import html
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "docs" / "src"
BUILD_DIR = ROOT / "docs" / "build"
PDFGEN = ROOT / "tools" / "pdfgen"
OUT_PDF = ROOT / "docs" / "分布式事务详解.pdf"

TITLE = "Java 分布式事务详解"
SUBTITLE = "从 2PC 到最终一致：原理、代码与生产实践"
VERSION = "2026-11 · 配套工程 dtx-demo"


# --------------------------------------------------------------------------- inline

def inline(text: str) -> str:
    """Escape and apply the small inline subset we use: code, bold, italic."""
    text = html.escape(text, quote=False)
    codes: list[str] = []

    def stash(match: re.Match) -> str:
        codes.append(match.group(1))
        return f"\x00{len(codes) - 1}\x00"

    text = re.sub(r"`([^`]+)`", stash, text)
    # Chinese typography: turn paired ASCII quotes into curly quotes
    text = re.sub(r'"([^"\n]*)"', "\u201c\\1\u201d", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<!\*)\*([^*\n]+)\*(?!\*)", r"<em>\1</em>", text)
    text = re.sub(r"\x00(\d+)\x00", lambda m: f"<code>{codes[int(m.group(1))]}</code>", text)
    return text


# --------------------------------------------------------------------------- blocks

class Doc:
    def __init__(self) -> None:
        self.body: list[str] = []
        self.headings: list[tuple[int, str, str]] = []  # level, id, text
        self.counter = 0

    def heading(self, level: int, text: str) -> None:
        self.counter += 1
        anchor = f"sec-{self.counter}"
        self.headings.append((level, anchor, text))
        self.body.append(
            f'<h{level} id="{anchor}">{inline(text)}</h{level}>'
        )


CODE_FENCE = re.compile(r"^```(\w*)\s*$")
TABLE_SEP = re.compile(r"^\|[\s:\-|]+\|$")
UL_ITEM = re.compile(r"^[-*]\s+(.*)$")
OL_ITEM = re.compile(r"^\d+\.\s+(.*)$")
# CJK runs inside code blocks use a Chinese font, everything else stays monospace
CJK_RUN = re.compile(r"([\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]+)")


def convert(markdown: str, doc: Doc) -> None:
    lines = markdown.splitlines()
    i = 0
    n = len(lines)

    while i < n:
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        if stripped.startswith("<!--"):
            i += 1
            continue

        # code fence
        m = CODE_FENCE.match(stripped)
        if m:
            lang = m.group(1)
            i += 1
            code_lines: list[str] = []
            while i < n and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            i += 1  # closing fence
            code = html.escape("\n".join(code_lines), quote=False)
            code = CJK_RUN.sub(r'<span class="cjk">\1</span>', code)
            cls = f' class="lang-{lang}"' if lang else ""
            doc.body.append(f"<pre{cls}><code>{code}</code></pre>")
            continue

        # headings
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            level = len(m.group(1))
            doc.heading(level, m.group(2).strip())
            i += 1
            continue

        # horizontal rule
        if re.match(r"^-{3,}$", stripped):
            doc.body.append("<hr />")
            i += 1
            continue

        # table
        if stripped.startswith("|") and i + 1 < n and TABLE_SEP.match(lines[i + 1].strip()):
            header_cells = [c.strip() for c in stripped.strip("|").split("|")]
            i += 2
            rows: list[list[str]] = []
            while i < n and lines[i].strip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            out = ['<table class="tbl">', "<thead><tr>"]
            out.extend(f"<th>{inline(c)}</th>" for c in header_cells)
            out.append("</tr></thead><tbody>")
            for row in rows:
                out.append("<tr>")
                out.extend(f"<td>{inline(c)}</td>" for c in row)
                out.append("</tr>")
            out.append("</tbody></table>")
            doc.body.append("".join(out))
            continue

        # blockquote
        if stripped.startswith(">"):
            quote: list[str] = []
            while i < n and lines[i].strip().startswith(">"):
                quote.append(lines[i].strip()[1:].strip())
                i += 1
            doc.body.append("<blockquote>" + inline(" ".join(quote)) + "</blockquote>")
            continue

        # unordered list
        if UL_ITEM.match(stripped):
            items: list[str] = []
            while i < n and UL_ITEM.match(lines[i].strip()):
                items.append(UL_ITEM.match(lines[i].strip()).group(1))
                i += 1
            doc.body.append("<ul>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ul>")
            continue

        # ordered list
        if OL_ITEM.match(stripped):
            items = []
            while i < n and OL_ITEM.match(lines[i].strip()):
                items.append(OL_ITEM.match(lines[i].strip()).group(1))
                i += 1
            doc.body.append("<ol>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ol>")
            continue

        # a line that is only bold text is a question / lead-in line: keep it on its own line
        if re.match(r"^\*\*[^*]+\*\*$", stripped):
            doc.body.append(f'<p class="q">{inline(stripped)}</p>')
            i += 1
            continue

        # paragraph
        para: list[str] = []
        while i < n:
            current = lines[i].strip()
            if (not current or current.startswith("#") or current.startswith("|")
                    or current.startswith(">") or CODE_FENCE.match(current)
                    or UL_ITEM.match(current) or OL_ITEM.match(current)
                    or re.match(r"^-{3,}$", current)):
                break
            para.append(current)
            i += 1
        doc.body.append(f"<p>{inline(' '.join(para))}</p>")


# --------------------------------------------------------------------------- page

CSS = """
@page {
  size: A4;
  margin: 24mm 18mm 20mm 18mm;
  @top-left { content: "Java 分布式事务详解"; font-family: "Microsoft YaHei", sans-serif;
              font-size: 8pt; color: #98a2b3; }
  @top-right { content: "dtx-demo 配套手册"; font-family: "Microsoft YaHei", sans-serif;
               font-size: 8pt; color: #98a2b3; }
  @bottom-center { content: counter(page); font-size: 9pt; color: #667085; }
}
@page cover { margin: 0; @top-left { content: none; } @top-right { content: none; }
              @bottom-center { content: none; } }

html { font-family: "Microsoft YaHei", "SimHei", sans-serif; font-size: 10.5pt; color: #1f2937; }
body { margin: 0; padding: 0; line-height: 1.72; }

.cover { page: cover; width: 210mm; height: 297mm; }
.cover .band { height: 96mm; width: 210mm; background-color: #12395f; }
.cover .band-inner { padding: 30mm 20mm 0 20mm; color: #ffffff; }
.cover .kicker { font-size: 10pt; letter-spacing: 2pt; color: #9fc4e8; }
.cover h1.cover-title { font-size: 34pt; color: #ffffff; margin: 8mm 0 0 0;
                        border: none; page-break-before: auto; line-height: 1.25; }
.cover .subtitle { font-size: 13pt; color: #cfe0f2; margin-top: 6mm; }
.cover .body { padding: 14mm 20mm 0 20mm; }
.cover .lead { font-size: 11pt; color: #344054; line-height: 1.9; }
.cover table.stats { width: 100%; margin-top: 12mm; border-collapse: collapse; }
.cover table.stats td { width: 33%; border-top: 2.2pt solid #12395f; padding: 4mm 6mm 0 0;
                        vertical-align: top; }
.cover .num { font-size: 22pt; color: #12395f; font-weight: bold; line-height: 1.2; }
.cover .lbl { font-size: 9.5pt; color: #667085; margin-top: 1mm; }
.cover .meta { margin-top: 30mm; border-top: 0.8pt solid #d0d5dd; padding-top: 5mm;
               font-size: 9.5pt; color: #667085; }
.cover .meta p { margin: 2mm 0; }

.toc { page-break-before: always; }
.toc h1 { page-break-before: auto; border: none; font-size: 20pt; margin-bottom: 7mm; }
table.toc-tbl { width: 100%; border-collapse: collapse; }
table.toc-tbl td { padding: 0 0 1.5mm 0; vertical-align: bottom; text-align: left; }
table.toc-tbl tr { page-break-inside: avoid; }
table.toc-tbl td.t { font-size: 10.2pt; }
table.toc-tbl td.t a { color: #1f2937; text-decoration: none; }
table.toc-tbl td.p { width: 16mm; text-align: right; font-size: 9.5pt; color: #667085; }
table.toc-tbl tr.lvl1 td.t { font-weight: bold; font-size: 10.8pt; padding-top: 3.2mm; }
table.toc-tbl tr.lvl1 td.p { padding-top: 3.2mm; }
table.toc-tbl tr.lvl2 td.t { padding-left: 6mm; font-size: 9.8pt; }
table.toc-tbl tr.lvl2 td.t a { color: #475467; }

h1 { font-size: 19pt; color: #12395f; margin: 0 0 7mm 0; padding-bottom: 3mm;
     border-bottom: 1.6pt solid #12395f; page-break-before: always;
     page-break-after: avoid; line-height: 1.35; }
h2 { font-size: 13.5pt; color: #1a4f8a; margin: 9mm 0 3mm 0;
     page-break-after: avoid; line-height: 1.4; }
h3 { font-size: 11.5pt; color: #243b53; margin: 6mm 0 2mm 0; page-break-after: avoid; }

p { margin: 0 0 3.2mm 0; text-align: justify; }
p.q { margin: 3.4mm 0 1.2mm 0; page-break-after: avoid; }
strong { color: #0f2f4f; }

ul, ol { margin: 0 0 3.5mm 0; padding-left: 7mm; }
li { margin: 0 0 1.8mm 0; text-align: justify; }

code { font-family: "Consolas", "Microsoft YaHei", monospace; font-size: 8.9pt; color: #b42318;
       background-color: #f4f5f7; padding: 0 1.2mm; }

pre { font-family: "Consolas", monospace; font-size: 8.5pt; line-height: 1.55;
      background-color: #f8f9fb; border-left: 2.4pt solid #2f6fb3;
      padding: 3.4mm 3.6mm; margin: 2.5mm 0 4mm 0; page-break-inside: avoid;
      white-space: pre-wrap; color: #1f2937; }
pre code { color: #1f2937; background-color: transparent; padding: 0; font-size: 8.5pt;
           font-family: inherit; }
.cjk { font-family: "Microsoft YaHei", "SimSun", sans-serif; font-size: 8.5pt; }

table.tbl { width: 100%; border-collapse: collapse; margin: 2.5mm 0 4.5mm 0;
            font-size: 9pt; page-break-inside: auto; }
table.tbl th { background-color: #e9f0f8; color: #12395f; border: 0.5pt solid #c4d2e3;
               padding: 2mm 2mm; text-align: left; font-weight: bold; }
table.tbl td { border: 0.5pt solid #d7dee8; padding: 1.8mm 2mm; vertical-align: top; }
table.tbl tr { page-break-inside: avoid; }

blockquote { margin: 2.5mm 0 4mm 0; padding: 2.6mm 4mm; background-color: #fff8e8;
             border-left: 2.4pt solid #e0a800; font-size: 9.6pt; color: #513c06; }

hr { border: none; border-top: 0.6pt solid #d0d5dd; margin: 6mm 0; }
"""


def build_html(doc: Doc, page_map: dict[str, int] | None = None) -> str:
    page_map = page_map or {}
    toc = ['<div class="toc"><h1>目 录</h1><table class="toc-tbl">']
    for level, anchor, text in doc.headings:
        if level > 2:
            continue
        cls = "lvl1" if level == 1 else "lvl2"
        page = page_map.get(anchor)
        number = f"{page}" if page else ""
        toc.append(
            f'<tr class="{cls}"><td class="t"><a href="#{anchor}">{inline(text)}</a></td>'
            f'<td class="p">{number}</td></tr>'
        )
    toc.append("</table></div>")

    cover = f"""
<div class="cover">
  <div class="band">
    <div class="band-inner">
      <div class="kicker">JAVA · DISTRIBUTED TRANSACTION</div>
      <h1 class="cover-title">{TITLE}</h1>
      <div class="subtitle">{SUBTITLE}</div>
    </div>
  </div>
  <div class="body">
    <div class="lead">
      从本地事务的边界出发，系统梳理 2PC/XA、TCC、SAGA、Seata AT、本地消息表与事务消息五类方案：
      模型怎么来、代码怎么写、生产上会踩哪些坑。<br />
      每个结论都能在配套工程 dtx-demo 的可运行用例里找到验证。
    </div>
    <table class="stats">
      <tr>
        <td><div class="num">5</div><div class="lbl">类分布式事务方案逐一拆解</div></td>
        <td><div class="num">3</div><div class="lbl">个可运行示例：TCC / SAGA / 可靠消息</div></td>
        <td><div class="num">21</div><div class="lbl">个用例验证空回滚、悬挂、补偿与幂等</div></td>
      </tr>
    </table>
    <div class="meta">
      <p>版本：{VERSION}</p>
      <p>配套工程：dtx-demo（Spring Boot 3.3 · Java 21）</p>
      <p>阅读建议：第 1-2 章建立框架，第 3-7 章按方案精读，第 8-10 章落地与选型</p>
    </div>
  </div>
</div>"""

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<title>{TITLE}</title>
<style type="text/css">{CSS}</style>
</head>
<body>
{cover}
{''.join(toc)}
{''.join(doc.body)}
</body>
</html>
"""


def main() -> int:
    sources = sorted(SRC_DIR.glob("*.md"))
    if not sources:
        print(f"no markdown sources in {SRC_DIR}", file=sys.stderr)
        return 2

    doc = Doc()
    for path in sources:
        convert(path.read_text(encoding="utf-8"), doc)

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    classpath = ";".join([
        str(PDFGEN / "target" / "classes"),
        str(PDFGEN / "target" / "lib" / "*"),
    ])

    def render(html_path: Path, pdf_path: Path) -> int:
        cmd = [
            "java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
            "-cp", classpath,
            "com.example.pdf.HtmlToPdf",
            str(html_path), str(pdf_path),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True,
                                encoding="utf-8", errors="replace")
        print(result.stdout.strip())
        if result.returncode != 0:
            print(result.stderr.strip(), file=sys.stderr)
        return result.returncode

    # pass 1: no page numbers in the toc
    draft_html = BUILD_DIR / "handbook-draft.xhtml"
    draft_pdf = BUILD_DIR / "handbook-draft.pdf"
    draft_html.write_text(build_html(doc), encoding="utf-8")
    if render(draft_html, draft_pdf) != 0:
        return 1

    # resolve page numbers by scanning the rendered text of every page
    mapping = BUILD_DIR / "toc-mapping.tsv"
    mapping.write_text(
        "\n".join(f"{anchor}\t{text}" for _, anchor, text in doc.headings if _ <= 2),
        encoding="utf-8",
    )
    index_out = BUILD_DIR / "toc-pages.tsv"
    result = subprocess.run([
        "java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
        "-cp", classpath, "com.example.pdf.PdfPageIndex",
        str(draft_pdf), str(mapping), str(index_out),
    ], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        return result.returncode

    page_map: dict[str, int] = {}
    for line in index_out.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        anchor, page = line.split("\t")
        if page and page != "0":
            page_map[anchor] = int(page)

    # pass 2: final document with page numbers
    final_html = BUILD_DIR / "handbook.xhtml"
    final_html.write_text(build_html(doc, page_map), encoding="utf-8")
    if render(final_html, OUT_PDF) != 0:
        return 1

    print(f"[build] {len(sources)} source file(s), {len(page_map)} toc entries -> {OUT_PDF}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
